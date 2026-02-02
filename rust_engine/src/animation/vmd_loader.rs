//! VMD 文件加载器 - 复刻 mdanceio 实现
//!
//! 解析 VMD 动画文件并转换为 Motion 数据

use std::io::{BufReader, Read, Seek};
use std::fs::File;
use std::path::Path;

use glam::{Vec3, Quat};
use byteorder::{LittleEndian, ReadBytesExt};

use crate::{MmdError, Result};
use crate::skeleton::BoneManager;
use crate::morph::MorphManager;

use super::motion::Motion;
use super::keyframe::{BoneKeyframe, MorphKeyframe};
use super::motion_track::BoneFrameTransform;

/// VMD 文件头
const VMD_HEADER_V1: &[u8] = b"Vocaloid Motion Data file";
const VMD_HEADER_V2: &[u8] = b"Vocaloid Motion Data 0002";

/// VMD 文件数据
#[derive(Debug, Clone)]
pub struct VmdFile {
    /// 模型名称
    pub model_name: String,
    /// Motion 数据
    pub motion: Motion,
}

impl VmdFile {
    /// 从文件路径加载 VMD
    pub fn load<P: AsRef<Path>>(path: P) -> Result<Self> {
        let file = File::open(path.as_ref())
            .map_err(|e| MmdError::Io(e))?;
        let mut reader = BufReader::new(file);
        Self::load_from_reader(&mut reader)
    }

    /// 从字节切片加载 VMD
    pub fn load_from_bytes(bytes: &[u8]) -> Result<Self> {
        let mut reader = std::io::Cursor::new(bytes);
        Self::load_from_reader(&mut reader)
    }

    /// 从 Reader 加载 VMD
    pub fn load_from_reader<R: Read + Seek>(reader: &mut R) -> Result<Self> {
        // 读取头部
        let mut header = [0u8; 30];
        reader.read_exact(&mut header)
            .map_err(|e| MmdError::VmdParse(format!("Failed to read header: {}", e)))?;

        // 验证头部 (两个头部都是 25 字节)
        let is_v1 = header[..25] == VMD_HEADER_V1[..];
        let is_v2 = header[..25] == VMD_HEADER_V2[..];
        
        if !is_v1 && !is_v2 {
            return Err(MmdError::VmdParse("Invalid VMD header".to_string()));
        }

        // 读取模型名称 (20 字节)
        let mut model_name_bytes = [0u8; 20];
        reader.read_exact(&mut model_name_bytes)
            .map_err(|e| MmdError::VmdParse(format!("Failed to read model name: {}", e)))?;
        let model_name = decode_shift_jis(&model_name_bytes);

        let mut motion = Motion::new();

        // 读取骨骼关键帧
        let bone_keyframe_count = reader.read_u32::<LittleEndian>()
            .map_err(|e| MmdError::VmdParse(format!("Failed to read bone keyframe count: {}", e)))?;

        for _ in 0..bone_keyframe_count {
            let (name, keyframe) = read_bone_keyframe(reader)?;
            motion.insert_bone_keyframe(&name, keyframe);
        }

        // 读取 Morph 关键帧
        let morph_keyframe_count = reader.read_u32::<LittleEndian>()
            .map_err(|e| MmdError::VmdParse(format!("Failed to read morph keyframe count: {}", e)))?;

        for _ in 0..morph_keyframe_count {
            let (name, keyframe) = read_morph_keyframe(reader)?;
            motion.insert_morph_keyframe(&name, keyframe);
        }

        // 跳过相机、光照等数据（我们只需要骨骼和 Morph）

        Ok(Self {
            model_name,
            motion,
        })
    }

    /// 获取最大帧数
    pub fn max_frame(&self) -> u32 {
        self.motion.duration()
    }
}

/// 读取骨骼关键帧
fn read_bone_keyframe<R: Read>(reader: &mut R) -> Result<(String, BoneKeyframe)> {
    // 骨骼名称 (15 字节)
    let mut name_bytes = [0u8; 15];
    reader.read_exact(&mut name_bytes)
        .map_err(|e| MmdError::VmdParse(format!("Failed to read bone name: {}", e)))?;
    let name = decode_shift_jis(&name_bytes);

    // 帧索引
    let frame_index = reader.read_u32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read frame index: {}", e)))?;

    // 平移 (x, y, z)
    let tx = reader.read_f32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read translation: {}", e)))?;
    let ty = reader.read_f32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read translation: {}", e)))?;
    let tz = reader.read_f32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read translation: {}", e)))?;

    // 旋转 (四元数 x, y, z, w)
    let rx = reader.read_f32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read rotation: {}", e)))?;
    let ry = reader.read_f32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read rotation: {}", e)))?;
    let rz = reader.read_f32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read rotation: {}", e)))?;
    let rw = reader.read_f32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read rotation: {}", e)))?;

    // 插值参数 (64 字节)
    let mut interpolation = [0u8; 64];
    reader.read_exact(&mut interpolation)
        .map_err(|e| MmdError::VmdParse(format!("Failed to read interpolation: {}", e)))?;

    // 提取插值参数
    // VMD 插值数据布局：每行 16 字节，共 4 行
    // 每行格式：X_x1, Y_x1, Z_x1, R_x1, X_y1, Y_y1, Z_y1, R_y1, ...
    let interpolation_x = [interpolation[0], interpolation[4], interpolation[8], interpolation[12]];
    let interpolation_y = [interpolation[1], interpolation[5], interpolation[9], interpolation[13]];
    let interpolation_z = [interpolation[2], interpolation[6], interpolation[10], interpolation[14]];
    let interpolation_r = [interpolation[3], interpolation[7], interpolation[11], interpolation[15]];

    // 坐标系转换：Z 轴和 W 分量反转
    let translation = Vec3::new(tx, ty, -tz);
    let orientation = Quat::from_xyzw(rx, ry, -rz, -rw).normalize();

    let keyframe = BoneKeyframe {
        frame_index,
        translation,
        orientation,
        interpolation_x,
        interpolation_y,
        interpolation_z,
        interpolation_r,
        is_physics_simulation_enabled: true,
    };

    Ok((name, keyframe))
}

/// 读取 Morph 关键帧
fn read_morph_keyframe<R: Read>(reader: &mut R) -> Result<(String, MorphKeyframe)> {
    // Morph 名称 (15 字节)
    let mut name_bytes = [0u8; 15];
    reader.read_exact(&mut name_bytes)
        .map_err(|e| MmdError::VmdParse(format!("Failed to read morph name: {}", e)))?;
    let name = decode_shift_jis(&name_bytes);

    // 帧索引
    let frame_index = reader.read_u32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read frame index: {}", e)))?;

    // 权重
    let weight = reader.read_f32::<LittleEndian>()
        .map_err(|e| MmdError::VmdParse(format!("Failed to read weight: {}", e)))?;

    let keyframe = MorphKeyframe {
        frame_index,
        weight,
    };

    Ok((name, keyframe))
}

/// 解码 Shift-JIS 字符串
fn decode_shift_jis(bytes: &[u8]) -> String {
    // 找到第一个 null 字节
    let end = bytes.iter().position(|&b| b == 0).unwrap_or(bytes.len());
    let bytes = &bytes[..end];
    
    // 使用 encoding_rs 解码
    let (decoded, _, _) = encoding_rs::SHIFT_JIS.decode(bytes);
    decoded.into_owned()
}

/// VMD 动画（运行时使用）
#[derive(Debug, Clone)]
pub struct VmdAnimation {
    /// Motion 数据
    motion: Motion,
}

impl VmdAnimation {
    /// 从 VmdFile 创建
    pub fn from_vmd_file(vmd: VmdFile) -> Self {
        Self {
            motion: vmd.motion,
        }
    }

    /// 从文件路径加载
    pub fn load<P: AsRef<Path>>(path: P) -> Result<Self> {
        let vmd = VmdFile::load(path)?;
        Ok(Self::from_vmd_file(vmd))
    }

    /// 从字节加载
    pub fn load_from_bytes(bytes: &[u8]) -> Result<Self> {
        let vmd = VmdFile::load_from_bytes(bytes)?;
        Ok(Self::from_vmd_file(vmd))
    }

    /// 获取最大帧数
    pub fn max_frame(&self) -> u32 {
        self.motion.duration()
    }

    /// 获取骨骼帧变换
    pub fn get_bone_transform(&self, name: &str, frame_index: u32, amount: f32) -> BoneFrameTransform {
        self.motion.find_bone_transform(name, frame_index, amount)
    }

    /// 获取 Morph 权重
    pub fn get_morph_weight(&self, name: &str, frame_index: u32, amount: f32) -> f32 {
        self.motion.find_morph_weight(name, frame_index, amount)
    }

    /// 评估动画并应用到骨骼和 Morph
    /// 
    /// # 参数
    /// - `frame`: 浮点帧数（支持帧间插值）
    /// - `bone_manager`: 骨骼管理器
    /// - `morph_manager`: Morph 管理器
    pub fn evaluate(
        &self,
        frame: f32,
        bone_manager: &mut BoneManager,
        morph_manager: &mut MorphManager,
    ) {
        self.evaluate_with_weight(frame, 1.0, bone_manager, morph_manager);
    }

    /// 带权重评估动画
    /// 
    /// # 参数
    /// - `frame`: 浮点帧数
    /// - `weight`: 混合权重 [0, 1]
    /// - `bone_manager`: 骨骼管理器
    /// - `morph_manager`: Morph 管理器
    pub fn evaluate_with_weight(
        &self,
        frame: f32,
        weight: f32,
        bone_manager: &mut BoneManager,
        morph_manager: &mut MorphManager,
    ) {
        let frame_index = frame.floor() as u32;
        let amount = frame.fract();

        // 应用骨骼动画
        for bone_name in self.motion.bone_track_names() {
            if let Some(bone_idx) = bone_manager.find_bone_by_name(bone_name) {
                let transform = self.motion.find_bone_transform(bone_name, frame_index, amount);
                
                if weight >= 1.0 {
                    // 完全覆盖
                    bone_manager.set_bone_translation(bone_idx, transform.translation);
                    bone_manager.set_bone_rotation(bone_idx, transform.orientation);
                } else if weight > 0.0 {
                    // 混合
                    if let Some(bone) = bone_manager.get_bone(bone_idx) {
                        let blended_translation = bone.animation_translate.lerp(transform.translation, weight);
                        let blended_rotation = bone.animation_rotate.slerp(transform.orientation, weight);
                        bone_manager.set_bone_translation(bone_idx, blended_translation);
                        bone_manager.set_bone_rotation(bone_idx, blended_rotation);
                    }
                }
            }
        }

        // 应用 Morph 动画
        for morph_name in self.motion.morph_track_names() {
            if let Some(morph_idx) = morph_manager.find_morph_by_name(morph_name) {
                let morph_weight = self.motion.find_morph_weight(morph_name, frame_index, amount);
                
                if weight >= 1.0 {
                    morph_manager.set_morph_weight(morph_idx, morph_weight);
                } else if weight > 0.0 {
                    let current = morph_manager.get_morph_weight(morph_idx);
                    let blended = current + (morph_weight - current) * weight;
                    morph_manager.set_morph_weight(morph_idx, blended);
                }
            }
        }
    }

    /// 检查是否包含骨骼轨道
    pub fn contains_bone_track(&self, name: &str) -> bool {
        self.motion.contains_bone_track(name)
    }

    /// 检查是否包含 Morph 轨道
    pub fn contains_morph_track(&self, name: &str) -> bool {
        self.motion.contains_morph_track(name)
    }

    /// 获取骨骼轨道名称列表
    pub fn bone_track_names(&self) -> Vec<String> {
        self.motion.bone_track_names().cloned().collect()
    }

    /// 获取 Morph 轨道名称列表
    pub fn morph_track_names(&self) -> Vec<String> {
        self.motion.morph_track_names().cloned().collect()
    }
}
