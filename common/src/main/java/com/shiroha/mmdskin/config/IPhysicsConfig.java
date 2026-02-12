package com.shiroha.mmdskin.config;

/**
 * 物理引擎配置子接口（Bullet3）
 *
 * Bullet3 内置处理阻尼、约束求解等，这里只保留可调节的高级参数。
 * 默认值与 Rust PhysicsConfig 保持一致。
 */
public interface IPhysicsConfig {

    /** 重力 Y 分量（负数向下），默认 -98.0（MMD 标准） */
    default float getPhysicsGravityY() { return -98.0f; }

    /** 物理 FPS（Bullet3 固定时间步），默认 60 */
    default float getPhysicsFps() { return 60.0f; }

    /** 每帧最大子步数，默认 4 */
    default int getPhysicsMaxSubstepCount() { return 4; }

    /** 求解器迭代次数，默认 4 */
    default int getPhysicsSolverIterations() { return 4; }

    /** PGS 迭代次数，默认 2 */
    default int getPhysicsPgsIterations() { return 2; }

    /** 最大校正速度，默认 0.1 */
    default float getPhysicsMaxCorrectiveVelocity() { return 0.1f; }

    /** 线性阻尼缩放，默认 0.3 */
    default float getPhysicsLinearDampingScale() { return 0.3f; }

    /** 角速度阻尼缩放，默认 0.2 */
    default float getPhysicsAngularDampingScale() { return 0.2f; }

    /** 质量缩放，默认 2.0 */
    default float getPhysicsMassScale() { return 2.0f; }

    /** 线性弹簧刚度缩放，默认 0.01 */
    default float getPhysicsLinearSpringStiffnessScale() { return 0.01f; }

    /** 角度弹簧刚度缩放，默认 0.01 */
    default float getPhysicsAngularSpringStiffnessScale() { return 0.01f; }

    /** 线性弹簧阻尼因子，默认 8.0 */
    default float getPhysicsLinearSpringDampingFactor() { return 8.0f; }

    /** 角度弹簧阻尼因子，默认 8.0 */
    default float getPhysicsAngularSpringDampingFactor() { return 8.0f; }

    /** 惯性效果强度（0.0=无惯性, 1.0=正常），默认 1.0 */
    default float getPhysicsInertiaStrength() { return 1.0f; }

    /** 最大线速度（防止物理爆炸），默认 1.0 */
    default float getPhysicsMaxLinearVelocity() { return 1.0f; }

    /** 最大角速度（防止物理爆炸），默认 1.0 */
    default float getPhysicsMaxAngularVelocity() { return 1.0f; }

    /** 是否启用胸部物理，默认 true */
    default boolean isPhysicsBustEnabled() { return true; }

    /** 胸部线性阻尼缩放，默认 1.5 */
    default float getPhysicsBustLinearDampingScale() { return 1.5f; }

    /** 胸部角速度阻尼缩放，默认 1.5 */
    default float getPhysicsBustAngularDampingScale() { return 1.5f; }

    /** 胸部质量缩放，默认 1.0 */
    default float getPhysicsBustMassScale() { return 1.0f; }

    /** 胸部线性弹簧刚度缩放，默认 10.0 */
    default float getPhysicsBustLinearSpringStiffnessScale() { return 10.0f; }

    /** 胸部角度弹簧刚度缩放，默认 10.0 */
    default float getPhysicsBustAngularSpringStiffnessScale() { return 10.0f; }

    /** 胸部线性弹簧阻尼因子，默认 3.0 */
    default float getPhysicsBustLinearSpringDampingFactor() { return 3.0f; }

    /** 胸部角度弹簧阻尼因子，默认 3.0 */
    default float getPhysicsBustAngularSpringDampingFactor() { return 3.0f; }

    /** 胸部防凹陷，默认 true */
    default boolean isPhysicsBustClampInward() { return true; }

    /** 是否启用关节（默认 true） */
    default boolean isPhysicsJointsEnabled() { return true; }

    /** 是否输出调试日志（默认 false） */
    default boolean isPhysicsDebugLog() { return false; }
}
