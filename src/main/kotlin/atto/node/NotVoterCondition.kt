package atto.node

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

class NotVoterCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        val propertyNotSet = env.getProperty("atto.node.private-key").isNullOrEmpty()
        val defaultProfileSet = env.activeProfiles.contains("default") || env.activeProfiles.isEmpty()
        return propertyNotSet || defaultProfileSet
    }
}
