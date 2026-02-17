package com.maddrobot.plugins.udm.ui;

import com.intellij.ui.CheckboxTreeBase;

public final class CheckboxPolicies {
    private CheckboxPolicies() {
    }

    public static CheckboxTreeBase.CheckPolicy propagateEverything() {
        return new CheckboxTreeBase.CheckPolicy(true, true, true, true);
    }
}
