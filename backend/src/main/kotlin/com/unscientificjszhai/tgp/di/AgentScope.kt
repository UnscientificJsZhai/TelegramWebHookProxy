package com.unscientificjszhai.tgp.di

import javax.inject.Scope

/**
 * 标记代理服务依赖的 Dagger 作用域。
 *
 * 使用相同子组件创建的带此注解依赖会在该子组件存活期间复用。
 */
@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class AgentScope
