package com.forband.event;

/** 活动三类（docs/04）。排练=全团、声部课=单声部、彩排=全团（DECISIONS D8）。 */
public final class EventKind {

    public static final String REHEARSAL = "REHEARSAL";
    public static final String SECTION_CLASS = "SECTION_CLASS";
    public static final String DRESS_REHEARSAL = "DRESS_REHEARSAL";

    private EventKind() {
    }

    public static boolean isSectionClass(String kind) {
        return SECTION_CLASS.equals(kind);
    }
}
