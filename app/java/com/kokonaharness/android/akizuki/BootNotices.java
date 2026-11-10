package com.kokonaharness.android.akizuki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The boot screen's notice model: one headline, everything else in a quiet list.
 *
 * <p>The screen used to print whatever single string the engine happened to emit,
 * so three unrelated facts (an engine failure, a permission that is off, a
 * rendering fallback) all arrived with the same weight and the user had no way to
 * tell which one actually needed them.</p>
 *
 * <p>Rule — keep this in one place, every future notice goes through it:</p>
 * <ul>
 *   <li>{@link #PRIMARY}: blocks using KHA right now, or needs a decision before
 *       anything else can happen (engine stopped, a mode selected that cannot
 *       work without a permission).</li>
 *   <li>{@link #SECONDARY}: worth knowing, safe to ignore until later (a
 *       capability that is merely off, a rendering fallback in effect, an
 *       optional update).</li>
 * </ul>
 *
 * <p>At most one primary is ever shown: {@link #primary} keeps the first call and
 * demotes later ones, so two call sites can never compete for the headline no
 * matter what order they run in.</p>
 */
public final class BootNotices {

    public static final int PRIMARY = 0;
    public static final int SECONDARY = 1;

    public static final class Item {
        public final int tier;
        public final String text;
        public final String actionLabel;
        public final Runnable action;

        Item(int tier, String text, String actionLabel, Runnable action) {
            this.tier = tier;
            this.text = text;
            this.actionLabel = actionLabel;
            this.action = action;
        }

        public boolean hasAction() {
            return action != null && actionLabel != null;
        }
    }

    private final List<Item> items = new ArrayList<Item>();
    private boolean primaryTaken;

    /** Adds a headline notice; becomes the secondary list if one already exists. */
    public BootNotices primary(String text, String actionLabel, Runnable action) {
        if (text == null || text.isEmpty()) return this;
        if (primaryTaken) return secondary(text, actionLabel, action);
        primaryTaken = true;
        items.add(new Item(PRIMARY, text, actionLabel, action));
        return this;
    }

    public BootNotices primary(String text) {
        return primary(text, null, null);
    }

    public BootNotices secondary(String text, String actionLabel, Runnable action) {
        if (text == null || text.isEmpty()) return this;
        items.add(new Item(SECONDARY, text, actionLabel, action));
        return this;
    }

    public BootNotices secondary(String text) {
        return secondary(text, null, null);
    }

    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    public Item headline() {
        for (Item item : items) {
            if (item.tier == PRIMARY) return item;
        }
        return null;
    }

    public List<Item> secondaryItems() {
        List<Item> out = new ArrayList<Item>();
        for (Item item : items) {
            if (item.tier == SECONDARY) out.add(item);
        }
        return out;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
