/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.application.options.codeStyle.arrangement.animation;

import com.intellij.application.options.codeStyle.arrangement.ArrangementConstants;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * @author Denis Zhdanov
 * @since 11/8/12 10:46 AM
 */
public class ArrangementAnimationManager implements ArrangementAnimationPanel.Listener, ActionListener {

  @NotNull private final Timer myTimer = new Timer(ArrangementConstants.ANIMATION_STEPS_TIME_GAP_MILLIS, this);

  @NotNull private final ArrangementAnimationPanel myAnimationPanel;
  @NotNull private final Callback                  myCallback;

  public ArrangementAnimationManager(@NotNull ArrangementAnimationPanel panel, @NotNull Callback callback) {
    myAnimationPanel = panel;
    myCallback = callback;
    myAnimationPanel.setListener(this);
  }
  
  @Override
  public void actionPerformed(ActionEvent e) {
    if (myTimer.isRunning()) {
      return;
    }
    myTimer.restart();
  }

  @Override
  public void onPaint() {
    boolean finished = !myAnimationPanel.nextIteration();
    myTimer.stop();
    myCallback.onAnimationIteration(finished);
  }

  public interface Callback {
    void onAnimationIteration(boolean finished);
  }
}
