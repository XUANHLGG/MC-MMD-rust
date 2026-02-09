//! Morph 管理器

use std::collections::HashMap;
use glam::{Vec2, Vec3};

use crate::skeleton::BoneManager;
use super::{Morph, MorphType, MaterialMorphOffset};

/// 材质 Morph 结果（每材质一个，累计所有 Material Morph 的影响）
#[derive(Clone, Debug)]
pub struct MaterialMorphResult {
    pub diffuse: glam::Vec4,
    pub specular: Vec3,
    pub specular_strength: f32,
    pub ambient: Vec3,
    pub edge_color: glam::Vec4,
    pub edge_size: f32,
    pub texture_tint: glam::Vec4,
    pub environment_tint: glam::Vec4,
    pub toon_tint: glam::Vec4,
}

impl MaterialMorphResult {
    pub fn new() -> Self {
        Self {
            diffuse: glam::Vec4::new(1.0, 1.0, 1.0, 1.0),
            specular: Vec3::new(1.0, 1.0, 1.0),
            specular_strength: 1.0,
            ambient: Vec3::new(1.0, 1.0, 1.0),
            edge_color: glam::Vec4::new(0.0, 0.0, 0.0, 1.0),
            edge_size: 1.0,
            texture_tint: glam::Vec4::new(1.0, 1.0, 1.0, 1.0),
            environment_tint: glam::Vec4::new(1.0, 1.0, 1.0, 1.0),
            toon_tint: glam::Vec4::new(1.0, 1.0, 1.0, 1.0),
        }
    }
    
    pub fn reset(&mut self) {
        *self = Self::new();
    }
    
    /// 乘算混合 (operation=0)
    pub fn apply_multiply(&mut self, offset: &MaterialMorphOffset, weight: f32) {
        let lerp_vec4 = |base: glam::Vec4, target: glam::Vec4, w: f32| -> glam::Vec4 {
            base * (glam::Vec4::ONE + (target - glam::Vec4::ONE) * w)
        };
        let lerp_vec3 = |base: Vec3, target: Vec3, w: f32| -> Vec3 {
            base * (Vec3::ONE + (target - Vec3::ONE) * w)
        };
        self.diffuse = lerp_vec4(self.diffuse, offset.diffuse, weight);
        self.specular = lerp_vec3(self.specular, offset.specular, weight);
        self.specular_strength *= 1.0 + (offset.specular_strength - 1.0) * weight;
        self.ambient = lerp_vec3(self.ambient, offset.ambient, weight);
        self.edge_color = lerp_vec4(self.edge_color, offset.edge_color, weight);
        self.edge_size *= 1.0 + (offset.edge_size - 1.0) * weight;
        self.texture_tint = lerp_vec4(self.texture_tint, offset.texture_tint, weight);
        self.environment_tint = lerp_vec4(self.environment_tint, offset.environment_tint, weight);
        self.toon_tint = lerp_vec4(self.toon_tint, offset.toon_tint, weight);
    }
    
    /// 加算混合 (operation=1)
    pub fn apply_additive(&mut self, offset: &MaterialMorphOffset, weight: f32) {
        self.diffuse += offset.diffuse * weight;
        self.specular += offset.specular * weight;
        self.specular_strength += offset.specular_strength * weight;
        self.ambient += offset.ambient * weight;
        self.edge_color += offset.edge_color * weight;
        self.edge_size += offset.edge_size * weight;
        self.texture_tint += offset.texture_tint * weight;
        self.environment_tint += offset.environment_tint * weight;
        self.toon_tint += offset.toon_tint * weight;
    }
    
    /// 将结果平坦化为 28 个 float
    pub fn to_flat_floats(&self) -> [f32; 28] {
        [
            self.diffuse.x, self.diffuse.y, self.diffuse.z, self.diffuse.w,
            self.specular.x, self.specular.y, self.specular.z,
            self.specular_strength,
            self.ambient.x, self.ambient.y, self.ambient.z,
            self.edge_color.x, self.edge_color.y, self.edge_color.z, self.edge_color.w,
            self.edge_size,
            self.texture_tint.x, self.texture_tint.y, self.texture_tint.z, self.texture_tint.w,
            self.environment_tint.x, self.environment_tint.y, self.environment_tint.z, self.environment_tint.w,
            self.toon_tint.x, self.toon_tint.y, self.toon_tint.z, self.toon_tint.w,
        ]
    }
}

impl Default for MaterialMorphResult {
    fn default() -> Self {
        Self::new()
    }
}

/// Morph 管理器
pub struct MorphManager {
    morphs: Vec<Morph>,
    name_to_index: HashMap<String, usize>,
    
    /// UV Morph 偏移（每顶点一个 Vec2）
    pub uv_morph_deltas: Vec<Vec2>,
    
    /// 材质 Morph 结果（每材质一个）
    pub material_morph_results: Vec<MaterialMorphResult>,
    
    /// 材质 Morph 结果平坦化缓存
    material_morph_results_flat_cache: Vec<f32>,
    
    // GPU UV Morph 数据（密集格式，供 Compute Shader 使用）
    gpu_uv_morph_offsets: Vec<f32>,
    gpu_uv_morph_weights: Vec<f32>,
    gpu_uv_morph_initialized: bool,
    uv_morph_indices: Vec<usize>,
}

impl MorphManager {
    pub fn new() -> Self {
        Self {
            morphs: Vec::new(),
            name_to_index: HashMap::new(),
            uv_morph_deltas: Vec::new(),
            material_morph_results: Vec::new(),
            material_morph_results_flat_cache: Vec::new(),
            gpu_uv_morph_offsets: Vec::new(),
            gpu_uv_morph_weights: Vec::new(),
            gpu_uv_morph_initialized: false,
            uv_morph_indices: Vec::new(),
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
    
    /// 初始化材质 Morph 结果缓冲区
    pub fn init_material_morph_results(&mut self, material_count: usize) {
        self.material_morph_results = vec![MaterialMorphResult::new(); material_count];
        self.material_morph_results_flat_cache = vec![0.0; material_count * 28];
    }
    
    /// 初始化 UV Morph 偏移缓冲区
    pub fn init_uv_morph_deltas(&mut self, vertex_count: usize) {
        self.uv_morph_deltas = vec![Vec2::ZERO; vertex_count];
    }
    
    /// 获取材质 Morph 结果数量
    pub fn get_material_morph_result_count(&self) -> usize {
        self.material_morph_results.len()
    }
    
    /// 获取材质 Morph 结果平坦化数据
    pub fn get_material_morph_results_flat(&mut self) -> &[f32] {
        let count = self.material_morph_results.len();
        self.material_morph_results_flat_cache.resize(count * 28, 0.0);
        for (i, result) in self.material_morph_results.iter().enumerate() {
            let flat = result.to_flat_floats();
            let start = i * 28;
            self.material_morph_results_flat_cache[start..start + 28].copy_from_slice(&flat);
        }
        &self.material_morph_results_flat_cache
    }
    
    // ========== GPU UV Morph 相关 ==========
    
    /// 初始化 GPU UV Morph 数据（将稀疏偏移转化为密集格式）
    pub fn init_gpu_uv_morph_data(&mut self, vertex_count: usize) {
        // 收集所有 UV Morph 的索引
        self.uv_morph_indices = self.morphs.iter().enumerate()
            .filter(|(_, m)| matches!(m.morph_type, MorphType::Uv | MorphType::AdditionalUv1))
            .map(|(i, _)| i)
            .collect();
        
        let uv_morph_count = self.uv_morph_indices.len();
        if uv_morph_count == 0 {
            self.gpu_uv_morph_initialized = true;
            return;
        }
        
        // 每个 UV Morph 对每个顶点存储 2 个 float (u, v)
        self.gpu_uv_morph_offsets = vec![0.0; uv_morph_count * vertex_count * 2];
        self.gpu_uv_morph_weights = vec![0.0; uv_morph_count];
        
        for (dense_idx, &morph_idx) in self.uv_morph_indices.iter().enumerate() {
            if let Some(morph) = self.morphs.get(morph_idx) {
                for offset in &morph.uv_offsets {
                    let vid = offset.vertex_index as usize;
                    if vid < vertex_count {
                        let base = dense_idx * vertex_count * 2 + vid * 2;
                        self.gpu_uv_morph_offsets[base] = offset.offset.x;
                        self.gpu_uv_morph_offsets[base + 1] = offset.offset.y;
                    }
                }
            }
        }
        
        self.gpu_uv_morph_initialized = true;
    }
    
    /// 同步 GPU UV Morph 权重
    pub fn sync_gpu_uv_morph_weights(&mut self) {
        for (dense_idx, &morph_idx) in self.uv_morph_indices.iter().enumerate() {
            if dense_idx < self.gpu_uv_morph_weights.len() {
                self.gpu_uv_morph_weights[dense_idx] = 
                    self.morphs.get(morph_idx).map(|m| m.weight).unwrap_or(0.0);
            }
        }
    }
    
    pub fn get_uv_morph_count(&self) -> usize {
        self.uv_morph_indices.len()
    }
    
    pub fn get_gpu_uv_morph_offsets_ptr(&self) -> (*const f32, usize) {
        (self.gpu_uv_morph_offsets.as_ptr(), self.gpu_uv_morph_offsets.len() * 4)
    }
    
    pub fn get_gpu_uv_morph_offsets_size(&self) -> usize {
        self.gpu_uv_morph_offsets.len() * 4
    }
    
    pub fn get_gpu_uv_morph_weights_ptr(&self) -> (*const f32, usize) {
        (self.gpu_uv_morph_weights.as_ptr(), self.gpu_uv_morph_weights.len())
    }
    
    pub fn is_gpu_uv_morph_initialized(&self) -> bool {
        self.gpu_uv_morph_initialized
    }
    
    // ========== Morph 应用 ==========
    
    /// 应用所有 Morph
    ///
    /// 流程:
    /// 1. 重置材质 Morph 结果和 UV 偏移
    /// 2. 遍历所有 Morph，按类型分发处理
    /// 3. Group Morph 递归展开子项
    pub fn apply_morphs(&mut self, bone_manager: &mut BoneManager, positions: &mut [Vec3]) {
        // 重置材质 Morph 结果
        for result in &mut self.material_morph_results {
            result.reset();
        }
        // 重置 UV 偏移
        for delta in &mut self.uv_morph_deltas {
            *delta = Vec2::ZERO;
        }
        
        // 收集需要处理的 morph 索引和权重（避免借用冲突）
        let active_morphs: Vec<(usize, f32)> = self.morphs.iter().enumerate()
            .filter(|(_, m)| m.weight.abs() > 0.001)
            .map(|(i, m)| (i, m.weight))
            .collect();
        
        for (morph_idx, weight) in active_morphs {
            self.apply_single_morph(morph_idx, weight, bone_manager, positions, 0);
        }
    }
    
    /// 应用单个 Morph（支持递归，depth 用于防止无限循环）
    fn apply_single_morph(
        &mut self,
        morph_idx: usize,
        effective_weight: f32,
        bone_manager: &mut BoneManager,
        positions: &mut [Vec3],
        depth: u32,
    ) {
        // 防止无限递归（Group Morph 可能循环引用）
        if depth > 16 || effective_weight.abs() < 0.001 {
            return;
        }
        
        // 安全地获取 morph 数据的副本以避免借用冲突
        let morph_type;
        let vertex_offsets;
        let bone_offsets;
        let material_offsets;
        let uv_offsets;
        let group_offsets;
        
        if let Some(morph) = self.morphs.get(morph_idx) {
            morph_type = morph.morph_type.clone();
            vertex_offsets = morph.vertex_offsets.clone();
            bone_offsets = morph.bone_offsets.clone();
            material_offsets = morph.material_offsets.clone();
            uv_offsets = morph.uv_offsets.clone();
            group_offsets = morph.group_offsets.clone();
        } else {
            return;
        }
        
        match morph_type {
            MorphType::Vertex => {
                Self::apply_vertex_morph_static(&vertex_offsets, effective_weight, positions);
            }
            MorphType::Bone => {
                Self::apply_bone_morph_static(&bone_offsets, effective_weight, bone_manager);
            }
            MorphType::Group | MorphType::Flip => {
                for sub in &group_offsets {
                    let sub_idx = sub.morph_index as usize;
                    if sub_idx < self.morphs.len() && sub_idx != morph_idx {
                        let sub_weight = effective_weight * sub.influence;
                        self.apply_single_morph(sub_idx, sub_weight, bone_manager, positions, depth + 1);
                    }
                }
            }
            MorphType::Material => {
                self.apply_material_morph_offsets(&material_offsets, effective_weight);
            }
            MorphType::Uv | MorphType::AdditionalUv1 => {
                self.apply_uv_morph_offsets(&uv_offsets, effective_weight);
            }
            _ => {
                // AdditionalUv2/3/4, Impulse 暂不处理
            }
        }
    }
    
    /// 应用顶点 Morph（静态方法，不需要 &self）
    fn apply_vertex_morph_static(
        offsets: &[super::VertexMorphOffset],
        weight: f32,
        positions: &mut [Vec3],
    ) {
        for offset in offsets {
            let idx = offset.vertex_index as usize;
            if idx < positions.len() {
                positions[idx] += offset.offset * weight;
            }
        }
    }
    
    /// 应用骨骼 Morph（静态方法，不需要 &self）
    fn apply_bone_morph_static(
        offsets: &[super::BoneMorphOffset],
        weight: f32,
        bone_manager: &mut BoneManager,
    ) {
        for offset in offsets {
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
    
    /// 应用材质 Morph 偏移
    ///
    /// material_index == -1 表示应用到所有材质。
    /// operation: 0=乘算, 1=加算
    fn apply_material_morph_offsets(
        &mut self,
        offsets: &[MaterialMorphOffset],
        weight: f32,
    ) {
        for offset in offsets {
            if offset.material_index < 0 {
                // -1 表示应用到所有材质
                for result in &mut self.material_morph_results {
                    if offset.operation == 0 {
                        result.apply_multiply(offset, weight);
                    } else {
                        result.apply_additive(offset, weight);
                    }
                }
            } else {
                let mat_idx = offset.material_index as usize;
                if mat_idx < self.material_morph_results.len() {
                    if offset.operation == 0 {
                        self.material_morph_results[mat_idx].apply_multiply(offset, weight);
                    } else {
                        self.material_morph_results[mat_idx].apply_additive(offset, weight);
                    }
                }
            }
        }
    }
    
    /// 应用 UV Morph 偏移
    fn apply_uv_morph_offsets(
        &mut self,
        offsets: &[super::UvMorphOffset],
        weight: f32,
    ) {
        for offset in offsets {
            let idx = offset.vertex_index as usize;
            if idx < self.uv_morph_deltas.len() {
                // 只取 x, y 分量作为 UV 偏移
                self.uv_morph_deltas[idx] += Vec2::new(offset.offset.x, offset.offset.y) * weight;
            }
        }
    }
}

impl Default for MorphManager {
    fn default() -> Self {
        Self::new()
    }
}
