/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

/** Java mirror of base_plugin.HookResult (exteraGram plugin SDK compatible). */
public class HookResult {
    public static final int DEFAULT = 0;
    public static final int CANCEL = 1;
    public static final int MODIFY = 2;
    public static final int MODIFY_FINAL = 3;

    public int strategy = DEFAULT;
    public Object request;
    public Object response;
    public Object update;
    public Object updates;
    public Object error;
    public Object params;

    public HookResult() {
    }

    public HookResult(int strategy) {
        this.strategy = strategy;
    }

    public boolean isCancel() {
        return strategy == CANCEL;
    }

    public boolean isModify() {
        return strategy == MODIFY || strategy == MODIFY_FINAL;
    }

    public boolean isFinal() {
        return strategy == MODIFY_FINAL || strategy == CANCEL;
    }

    public boolean isDefault() {
        return strategy == DEFAULT;
    }
}
