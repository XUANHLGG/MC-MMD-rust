//! Morph 管理器

use std::collections::HashMap;
use glam::Vec3;

use crate::skeleton::BoneManager;
use super::{Morph, MorphType};

/// Morph 管理器
pub struct MorphManager {
    morphs: Vec<Morph>,
    name_to_index: HashMap<String, usize>,
}

impl MorphManager {
    pub fn new() -> Self {
        Self {
            morphs: Vec::new(),
            name_to_index: HashMap::new(),
        }
    }
    
    /// 添加 Morph
    pub fn add_morph(&mut self, morph: Morph) {
        let index = self.morphs.len();
        self.name_to_index.insert(morph.name.clone(), index);
        self.morphs.push(morph);
    }
    
    /// 通过名称查找 Morph
    pub fn find_morph_by_name(&self, name: &str) -> Option<usize> {
        self.name_to_index.get(name).copied()
    }
    
    /// 获取 Morph 数量
    pub fn morph_count(&self) -> usize {
        self.morphs.len()
    }
    
    /// 获取 Morph
    pub fn get_morph(&self, index: usize) -> Option<&Morph> {
        self.morphs.get(index)
    }
    
    /// 获取可变 Morph 引用
    pub fn get_morph_mut(&mut self, index: usize) -> Option<&mut Morph> {
        self.morphs.get_mut(index)
    }
    
    /// 设置 Morph 权重
    pub fn set_morph_weight(&mut self, index: usize, weight: f32) {
        if let Some(morph) = self.morphs.get_mut(index) {
            morph.set_weight(weight);
        }
    }
    
    /// 获取 Morph 权重
    pub fn get_morph_weight(&self, index: usize) -> f32 {
        self.morphs.get(index).map(|m| m.weight).unwrap_or(0.0)
    }
    
    /// 重置所有 Morph 权重
    pub fn reset_all_weights(&mut self) {
        for morph in &mut self.morphs {
            morph.reset();
        }
    }
    
    /// 应用所有 Morph 到顶点和骨骼
    pub fn apply_morphs(&self, bone_manager: &mut BoneManager, positions: &mut [Vec3]) {
        for morph in &self.morphs {
            if morph.weight <= 0.0 {
                continue;
            }
            
            match morph.morph_type {
                MorphType::Vertex => {
                    self.apply_vertex_morph(morph, positions);
                }
                MorphType::Bone => {
                    self.apply_bone_morph(morph, bone_manager);
                }
                MorphType::Group => {
                    // 组 Morph 需要递归处理
                    // 暂时跳过复杂的组 Morph
                }
                _ => {
                    // UV 和材质 Morph 暂不处理
                }
            }
        }
    }
    
    /// 应用顶点 Morph
    fn apply_vertex_morph(&self, morph: &Morph, positions: &mut [Vec3]) {
        let weight = morph.weight;
        
        for offset in &morph.vertex_offsets {
            let idx = offset.vertex_index as usize;
            if idx < positions.len() {
                positions[idx] += offset.offset * weight;
            }
        }
    }
    
    /// 应用骨骼 Morph
    fn apply_bone_morph(&self, morph: &Morph, bone_manager: &mut BoneManager) {
        let weight = morph.weight;
        
        for offset in &morph.bone_offsets {
            let idx = offset.bone_index as usize;
            let translation = offset.translation * weight;
            let rotation = glam::Quat::from_xyzw(
                offset.rotation.x * weight,
                offset.rotation.y * weight,
                offset.rotation.z * weight,
                1.0 - (1.0 - offset.rotation.w) * weight,
            ).normalize();
            
            if let Some(bone) = bone_manager.get_bone_mut(idx) {
                bone.animation_translate += translation;
                bone.animation_rotate = bone.animation_rotate * rotation;
            }
        }
    }
}

impl Default for MorphManager {
    fn default() -> Self {
        Self::new()
    }
}
