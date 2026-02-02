//! 动画轨道 - 复刻 mdanceio 实现
//!
//! 存储单个骨骼或 Morph 的所有关键帧，并提供查找和插值功能

use std::collections::BTreeMap;
use glam::{Vec3, Quat};

use super::bezier_curve::BezierCurveFactory;
use super::interpolation::{
    coefficient, lerp_element_wise, lerp_f32, 
    BoneKeyframeInterpolation, KeyframeInterpolationPoint,
};
use super::keyframe::{BoneKeyframe, MorphKeyframe};

/// 骨骼帧变换结果
#[derive(Debug, Clone, Copy)]
pub struct BoneFrameTransform {
    /// 平移
    pub translation: Vec3,
    /// 旋转
    pub orientation: Quat,
    /// 插值参数
    pub interpolation: BoneKeyframeInterpolation,
    /// 本地变换混合系数（用于物理过渡）
    pub local_transform_mix: Option<f32>,
    /// 是否启用物理
    pub enable_physics: bool,
    /// 是否禁用物理
    pub disable_physics: bool,
}

impl Default for BoneFrameTransform {
    fn default() -> Self {
        Self {
            translation: Vec3::ZERO,
            orientation: Quat::IDENTITY,
            interpolation: BoneKeyframeInterpolation::default(),
            local_transform_mix: None,
            enable_physics: false,
            disable_physics: false,
        }
    }
}

impl BoneFrameTransform {
    /// 混合平移（考虑 local_transform_mix）
    pub fn mixed_translation(&self, local_user_translation: Vec3) -> Vec3 {
        if let Some(coef) = self.local_transform_mix {
            local_user_translation.lerp(self.translation, coef)
        } else {
            self.translation
        }
    }

    /// 混合旋转（考虑 local_transform_mix）
    pub fn mixed_orientation(&self, local_user_orientation: Quat) -> Quat {
        if let Some(coef) = self.local_transform_mix {
            local_user_orientation.slerp(self.orientation, coef)
        } else {
            self.orientation
        }
    }
}

/// 动画轨道 trait
pub trait MotionTrack {
    type Frame;
    
    /// 查找精确帧
    fn find(&self, frame_index: u32) -> Option<Self::Frame>;
    
    /// 查找最近的前后帧索引
    fn search_closest(&self, frame_index: u32) -> (Option<u32>, Option<u32>);
    
    /// 求值指定帧
    fn seek(&self, frame_index: u32, bezier_factory: &dyn BezierCurveFactory) -> Self::Frame;
    
    /// 精确求值（支持帧间插值）
    fn seek_precisely(
        &self,
        frame_index: u32,
        amount: f32,
        bezier_factory: &dyn BezierCurveFactory,
    ) -> Self::Frame;
    
    /// 获取轨道长度
    fn len(&self) -> usize;
    
    /// 是否为空
    fn is_empty(&self) -> bool {
        self.len() == 0
    }
    
    /// 获取最大帧索引
    fn max_frame_index(&self) -> u32;
}

/// 骨骼动画轨道
#[derive(Debug, Clone)]
pub struct BoneMotionTrack {
    /// 关键帧映射（帧索引 -> 关键帧）
    pub keyframes: BTreeMap<u32, BoneKeyframe>,
}

impl BoneMotionTrack {
    pub fn new() -> Self {
        Self {
            keyframes: BTreeMap::new(),
        }
    }

    /// 插入关键帧
    pub fn insert_keyframe(&mut self, keyframe: BoneKeyframe) -> Option<BoneKeyframe> {
        self.keyframes.insert(keyframe.frame_index, keyframe)
    }

    /// 移除关键帧
    pub fn remove_keyframe(&mut self, frame_index: u32) -> Option<BoneKeyframe> {
        self.keyframes.remove(&frame_index)
    }

    /// 查找最近的前后关键帧
    fn search_closest_keyframes(&self, frame_index: u32) -> (Option<&BoneKeyframe>, Option<&BoneKeyframe>) {
        let mut prev = None;
        let mut next = None;
        
        for (idx, kf) in &self.keyframes {
            if *idx <= frame_index {
                prev = Some(kf);
            } else {
                next = Some(kf);
                break;
            }
        }
        
        (prev, next)
    }
}

impl Default for BoneMotionTrack {
    fn default() -> Self {
        Self::new()
    }
}

impl MotionTrack for BoneMotionTrack {
    type Frame = BoneFrameTransform;

    fn find(&self, frame_index: u32) -> Option<Self::Frame> {
        self.keyframes.get(&frame_index).map(|kf| BoneFrameTransform {
            translation: kf.translation,
            orientation: kf.orientation,
            interpolation: BoneKeyframeInterpolation::build(
                &kf.interpolation_x,
                &kf.interpolation_y,
                &kf.interpolation_z,
                &kf.interpolation_r,
            ),
            local_transform_mix: None,
            enable_physics: kf.is_physics_simulation_enabled,
            disable_physics: false,
        })
    }

    fn search_closest(&self, frame_index: u32) -> (Option<u32>, Option<u32>) {
        let mut prev = None;
        let mut next = None;
        
        for idx in self.keyframes.keys() {
            if *idx <= frame_index {
                prev = Some(*idx);
            } else {
                next = Some(*idx);
                break;
            }
        }
        
        (prev, next)
    }

    fn seek(&self, frame_index: u32, bezier_factory: &dyn BezierCurveFactory) -> Self::Frame {
        // 精确匹配
        if let Some(frame) = self.find(frame_index) {
            return frame;
        }
        
        // 查找前后关键帧
        let (prev_kf, next_kf) = self.search_closest_keyframes(frame_index);
        
        match (prev_kf, next_kf) {
            (Some(prev), Some(next)) => {
                let interval = next.frame_index - prev.frame_index;
                let coef = coefficient(prev.frame_index, next.frame_index, frame_index);
                
                let prev_enabled = prev.is_physics_simulation_enabled;
                let next_enabled = next.is_physics_simulation_enabled;
                
                // 物理状态变化处理
                if prev_enabled && !next_enabled {
                    BoneFrameTransform {
                        translation: next.translation,
                        orientation: next.orientation,
                        interpolation: BoneKeyframeInterpolation::build(
                            &next.interpolation_x,
                            &next.interpolation_y,
                            &next.interpolation_z,
                            &next.interpolation_r,
                        ),
                        local_transform_mix: Some(coef),
                        enable_physics: false,
                        disable_physics: true,
                    }
                } else {
                    // 正常插值
                    let translation_interpolation = [
                        KeyframeInterpolationPoint::new(&next.interpolation_x),
                        KeyframeInterpolationPoint::new(&next.interpolation_y),
                        KeyframeInterpolationPoint::new(&next.interpolation_z),
                    ];
                    
                    let amounts = Vec3::new(
                        translation_interpolation[0].curve_value(interval, coef, bezier_factory),
                        translation_interpolation[1].curve_value(interval, coef, bezier_factory),
                        translation_interpolation[2].curve_value(interval, coef, bezier_factory),
                    );
                    
                    let translation = lerp_element_wise(prev.translation, next.translation, amounts);
                    
                    let orientation_interpolation = KeyframeInterpolationPoint::new(&next.interpolation_r);
                    let amount = orientation_interpolation.curve_value(interval, coef, bezier_factory);
                    let orientation = prev.orientation.slerp(next.orientation, amount);
                    
                    BoneFrameTransform {
                        translation,
                        orientation,
                        interpolation: BoneKeyframeInterpolation::build(
                            &next.interpolation_x,
                            &next.interpolation_y,
                            &next.interpolation_z,
                            &next.interpolation_r,
                        ),
                        local_transform_mix: None,
                        enable_physics: prev_enabled && next_enabled,
                        disable_physics: false,
                    }
                }
            }
            (Some(prev), None) => {
                // 只有前帧，使用前帧数据
                BoneFrameTransform {
                    translation: prev.translation,
                    orientation: prev.orientation,
                    interpolation: BoneKeyframeInterpolation::build(
                        &prev.interpolation_x,
                        &prev.interpolation_y,
                        &prev.interpolation_z,
                        &prev.interpolation_r,
                    ),
                    local_transform_mix: None,
                    enable_physics: prev.is_physics_simulation_enabled,
                    disable_physics: false,
                }
            }
            (None, Some(next)) => {
                // 只有后帧，使用后帧数据
                BoneFrameTransform {
                    translation: next.translation,
                    orientation: next.orientation,
                    interpolation: BoneKeyframeInterpolation::build(
                        &next.interpolation_x,
                        &next.interpolation_y,
                        &next.interpolation_z,
                        &next.interpolation_r,
                    ),
                    local_transform_mix: None,
                    enable_physics: next.is_physics_simulation_enabled,
                    disable_physics: false,
                }
            }
            (None, None) => {
                // 无关键帧
                BoneFrameTransform::default()
            }
        }
    }

    fn seek_precisely(
        &self,
        frame_index: u32,
        amount: f32,
        bezier_factory: &dyn BezierCurveFactory,
    ) -> Self::Frame {
        let f0 = self.seek(frame_index, bezier_factory);
        
        if amount > 0.0 {
            let f1 = self.seek(frame_index + 1, bezier_factory);
            
            let local_transform_mix = match (f0.local_transform_mix, f1.local_transform_mix) {
                (Some(a0), Some(a1)) => Some(lerp_f32(a0, a1, amount)),
                (None, Some(a1)) => Some(amount * a1),
                (Some(a0), None) => Some((1.0 - amount) * a0),
                _ => None,
            };
            
            BoneFrameTransform {
                translation: f0.translation.lerp(f1.translation, amount),
                orientation: f0.orientation.slerp(f1.orientation, amount),
                interpolation: f0.interpolation.lerp(f1.interpolation, amount),
                local_transform_mix,
                enable_physics: f0.enable_physics && f1.enable_physics,
                disable_physics: f0.disable_physics || f1.disable_physics,
            }
        } else {
            f0
        }
    }

    fn len(&self) -> usize {
        self.keyframes.len()
    }

    fn max_frame_index(&self) -> u32 {
        self.keyframes.keys().last().copied().unwrap_or(0)
    }
}

/// Morph 动画轨道
#[derive(Debug, Clone)]
pub struct MorphMotionTrack {
    /// 关键帧映射（帧索引 -> 关键帧）
    pub keyframes: BTreeMap<u32, MorphKeyframe>,
}

impl MorphMotionTrack {
    pub fn new() -> Self {
        Self {
            keyframes: BTreeMap::new(),
        }
    }

    /// 插入关键帧
    pub fn insert_keyframe(&mut self, keyframe: MorphKeyframe) -> Option<MorphKeyframe> {
        self.keyframes.insert(keyframe.frame_index, keyframe)
    }

    /// 移除关键帧
    pub fn remove_keyframe(&mut self, frame_index: u32) -> Option<MorphKeyframe> {
        self.keyframes.remove(&frame_index)
    }

    /// 查找最近的前后关键帧
    fn search_closest_keyframes(&self, frame_index: u32) -> (Option<&MorphKeyframe>, Option<&MorphKeyframe>) {
        let mut prev = None;
        let mut next = None;
        
        for (idx, kf) in &self.keyframes {
            if *idx <= frame_index {
                prev = Some(kf);
            } else {
                next = Some(kf);
                break;
            }
        }
        
        (prev, next)
    }
}

impl Default for MorphMotionTrack {
    fn default() -> Self {
        Self::new()
    }
}

impl MotionTrack for MorphMotionTrack {
    type Frame = f32;

    fn find(&self, frame_index: u32) -> Option<Self::Frame> {
        self.keyframes.get(&frame_index).map(|kf| kf.weight)
    }

    fn search_closest(&self, frame_index: u32) -> (Option<u32>, Option<u32>) {
        let mut prev = None;
        let mut next = None;
        
        for idx in self.keyframes.keys() {
            if *idx <= frame_index {
                prev = Some(*idx);
            } else {
                next = Some(*idx);
                break;
            }
        }
        
        (prev, next)
    }

    fn seek(&self, frame_index: u32, _bezier_factory: &dyn BezierCurveFactory) -> Self::Frame {
        // 精确匹配
        if let Some(weight) = self.find(frame_index) {
            return weight;
        }
        
        // 查找前后关键帧
        let (prev_kf, next_kf) = self.search_closest_keyframes(frame_index);
        
        match (prev_kf, next_kf) {
            (Some(prev), Some(next)) => {
                // Morph 使用线性插值
                let coef = coefficient(prev.frame_index, next.frame_index, frame_index);
                lerp_f32(prev.weight, next.weight, coef)
            }
            (Some(prev), None) => prev.weight,
            (None, Some(next)) => next.weight,
            (None, None) => 0.0,
        }
    }

    fn seek_precisely(
        &self,
        frame_index: u32,
        amount: f32,
        bezier_factory: &dyn BezierCurveFactory,
    ) -> Self::Frame {
        let w0 = self.seek(frame_index, bezier_factory);
        
        if amount > 0.0 {
            let w1 = self.seek(frame_index + 1, bezier_factory);
            lerp_f32(w0, w1, amount)
        } else {
            w0
        }
    }

    fn len(&self) -> usize {
        self.keyframes.len()
    }

    fn max_frame_index(&self) -> u32 {
        self.keyframes.keys().last().copied().unwrap_or(0)
    }
}
