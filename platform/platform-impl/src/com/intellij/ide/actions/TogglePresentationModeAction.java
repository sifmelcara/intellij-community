// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.actions;

import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.idea.ActionsBundle;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.openapi.wm.impl.DesktopLayout;
import com.intellij.openapi.wm.impl.IdeFrameImpl;
import com.intellij.openapi.wm.impl.ProjectFrameHelper;
import com.intellij.ui.scale.JBUIScale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @author Konstantin Bulenkov
 */
public final class TogglePresentationModeAction extends AnAction implements DumbAware {
  private static final Map<Object, Object> ourSavedValues = new LinkedHashMap<>();
  private static float ourSavedScaleFactor = JBUIScale.scale(1f);
  private static float ourSavedConsoleFontSize;
  private static final Logger LOG = Logger.getInstance(TogglePresentationModeAction.class);

  @Override
  public void update(@NotNull AnActionEvent e) {
    boolean selected = UISettings.getInstance().getPresentationMode();
    e.getPresentation().setText(selected ? ActionsBundle.message("action.TogglePresentationMode.exit")
                                         : ActionsBundle.message("action.TogglePresentationMode.enter"));
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e){
    UISettings settings = UISettings.getInstance();
    Project project = e.getProject();

    setPresentationMode(project, !settings.getPresentationMode());
  }

  public static void setPresentationMode(@Nullable Project project, boolean inPresentation) {
    UISettings settings = UISettings.getInstance();
    settings.setPresentationMode(inPresentation);

    boolean layoutStored = project != null && storeToolWindows(project);

    tweakUIDefaults(settings, inPresentation);

    log(String.format("Will tweak full screen mode for presentation=%b", inPresentation));

    CompletableFuture<?> callback = project == null ? CompletableFuture.completedFuture(null) : tweakFrameFullScreen(project, inPresentation);
    callback.whenComplete((o, throwable) -> {
      tweakEditorAndFireUpdateUI(settings, inPresentation);

      if (layoutStored) {
        restoreToolWindows(project, inPresentation);
      }
    });
  }

  private static CompletableFuture<?> tweakFrameFullScreen(Project project, boolean inPresentation) {
    ProjectFrameHelper frame = ProjectFrameHelper.getFrameHelper(IdeFrameImpl.getActiveFrame());
    if (frame == null) {
      return CompletableFuture.completedFuture(null);
    }

    PropertiesComponent propertiesComponent = PropertiesComponent.getInstance(project);
    if (inPresentation) {
      propertiesComponent.setValue("full.screen.before.presentation.mode", String.valueOf(frame.isInFullScreen()));
      return frame.toggleFullScreen(true);
    }
    else if (frame.isInFullScreen()) {
      final String value = propertiesComponent.getValue("full.screen.before.presentation.mode");
      return frame.toggleFullScreen("true".equalsIgnoreCase(value));
    }
    return CompletableFuture.completedFuture(null);
  }

  private static void tweakEditorAndFireUpdateUI(UISettings settings, boolean inPresentation) {
    EditorColorsScheme globalScheme = EditorColorsManager.getInstance().getGlobalScheme();
    float fontSize = inPresentation ? settings.getPresentationModeFontSize() : globalScheme.getEditorFontSize2D();
    if (inPresentation) {
      ourSavedConsoleFontSize = globalScheme.getConsoleFontSize2D();
      globalScheme.setConsoleFontSize(fontSize);
    }
    else {
      globalScheme.setConsoleFontSize(ourSavedConsoleFontSize);
    }

    log(String.format("Will set editor font size %.1f for presentation=%b", fontSize, inPresentation));

    for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
      if (editor instanceof EditorEx) {
        ((EditorEx)editor).setFontSize(fontSize);
      }
    }
    UISettings.getInstance().fireUISettingsChanged();
    LafManager.getInstance().updateUI();
    EditorUtil.reinitSettings();
  }

  private static void tweakUIDefaults(UISettings settings, boolean inPresentation) {
    UIDefaults defaults = UIManager.getDefaults();
    Enumeration<Object> keys = defaults.keys();
    if (inPresentation) {
      while (keys.hasMoreElements()) {
        Object key = keys.nextElement();
        if (key instanceof String) {
          String name = (String)key;
          if (name.endsWith(".font") || name.endsWith(".acceleratorFont")) {
            Font font = defaults.getFont(key);
            ourSavedValues.put(key, font);
          }
          else if (name.endsWith(".rowHeight")) {
            ourSavedValues.put(key, defaults.getInt(key));
          }
        }
      }
      float scaleFactor = JBUIScale.getFontScale(settings.getPresentationModeFontSize());
      ourSavedScaleFactor = JBUIScale.scale(1f);
      JBUIScale.setUserScaleFactor(scaleFactor);
      for (Object key : ourSavedValues.keySet()) {
        Object v = ourSavedValues.get(key);
        if (v instanceof Font) {
          Font font = (Font)v;
          defaults.put(key, new FontUIResource(font.deriveFont(JBUIScale.scale(font.getSize2D()))));
        }
        else if (v instanceof Integer) {
          defaults.put(key, JBUIScale.scale(((Integer)v).intValue()));
        }
      }
    }
    else {
      for (Object key : ourSavedValues.keySet()) {
        defaults.put(key, ourSavedValues.get(key));
      }
      JBUIScale.setUserScaleFactor(ourSavedScaleFactor);
      ourSavedValues.clear();
    }
  }

  private static boolean hideAllToolWindows(@NotNull ToolWindowManagerEx manager) {
    // to clear windows stack
    manager.clearSideStack();

    boolean hasVisible = false;
    for (ToolWindow toolWindow : manager.getToolWindows()) {
      if (toolWindow.isVisible()) {
        toolWindow.hide();
        hasVisible = true;
      }
    }
    return hasVisible;
  }

  static boolean storeToolWindows(@NotNull Project project) {
    ToolWindowManagerEx manager = ToolWindowManagerEx.getInstanceEx(project);

    DesktopLayout layout = manager.getLayout().copy();
    boolean hasVisible = hideAllToolWindows(manager);
    if (hasVisible) {
      manager.setLayoutToRestoreLater(layout);
      manager.activateEditorComponent();
    }
    return hasVisible;
  }

  static void restoreToolWindows(@NotNull Project project, boolean inPresentation) {
    log(String.format("Will restore tool windows for presentation=%b", inPresentation));

    ToolWindowManagerEx manager = ToolWindowManagerEx.getInstanceEx(project);
    DesktopLayout restoreLayout = manager.getLayoutToRestoreLater();
    if (!inPresentation && restoreLayout != null) {
      manager.setLayout(restoreLayout);
    }
  }

  private static void log(String message) {
    if (ApplicationManager.getApplication().isEAP()) LOG.info(message);
    else LOG.debug(message);
  }
}
