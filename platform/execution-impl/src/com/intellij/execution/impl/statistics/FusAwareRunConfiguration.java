// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.impl.statistics;

import com.intellij.internal.statistic.eventLog.events.EventPair;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @see RunConfigurationUsageLanguageExtension
 * @see RunConfigurationTypeLanguageExtension
 */
public interface FusAwareRunConfiguration {
  @NotNull List<EventPair<?>> getAdditionalUsageData();
}
