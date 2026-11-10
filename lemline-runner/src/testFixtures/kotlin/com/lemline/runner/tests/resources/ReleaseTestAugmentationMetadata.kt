// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import io.quarkus.builder.BuildChainBuilder
import io.quarkus.builder.item.SimpleBuildItem
import io.quarkus.deployment.builditem.CombinedIndexBuildItem
import io.quarkus.deployment.builditem.LaunchModeBuildItem
import io.quarkus.deployment.builditem.TransformedClassesBuildItem
import io.quarkus.deployment.dev.RuntimeUpdatesProcessor
import io.quarkus.deployment.steps.ClassTransformingBuildStep
import io.quarkus.runtime.LaunchMode
import io.quarkus.test.junit.buildchain.TestBuildChainCustomizerProducer
import java.util.function.Consumer
import org.jboss.jandex.Index

/**
 * Quarkus 3.30.6 retains reload-only metadata for every application prepared during discovery.
 * Release the static owners after transformation; build items remain available to other steps.
 * Packaged in the fixtures JAR because the augmentation loader excludes application test classes.
 * Remove this workaround once both upstream fixes are included in our Quarkus version:
 * https://github.com/quarkusio/quarkus/pull/58216
 * https://github.com/quarkusio/quarkus/pull/58217
 */
class ReleaseTestAugmentationMetadata : TestBuildChainCustomizerProducer {
    override fun produce(testClassesIndex: Index): Consumer<BuildChainBuilder> = Consumer { chain ->
        chain.addBuildStep { context ->
            val mode = context.consume(LaunchModeBuildItem::class.java)
            if (mode.launchMode == LaunchMode.TEST && !mode.isAuxiliaryApplication) {
                RuntimeUpdatesProcessor.setLastStartIndex(null)
                // No public reset API exists in 3.30.6. Fail visibly if an upgrade changes this field.
                val transformers = ClassTransformingBuildStep::class.java.getDeclaredField("lastTransformers")
                transformers.isAccessible = true
                transformers.set(null, null)
            }
            context.produce(MetadataReleased())
        }
            .consumes(LaunchModeBuildItem::class.java)
            .consumes(CombinedIndexBuildItem::class.java)
            .consumes(TransformedClassesBuildItem::class.java)
            .produces(MetadataReleased::class.java)
            .build()
        chain.addFinal(MetadataReleased::class.java)
    }

    class MetadataReleased : SimpleBuildItem()
}
