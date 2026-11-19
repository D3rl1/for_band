package com.forband.piece;

/** 曲目附件的两类（docs/13 §13.3⑪）。 */
public final class AttachmentKind {

    public static final String AUDIO = "AUDIO";
    public static final String SCORE = "SCORE";

    private AttachmentKind() {
    }

    public static boolean isValid(String kind) {
        return AUDIO.equals(kind) || SCORE.equals(kind);
    }

    public static String label(String kind) {
        return switch (kind == null ? "" : kind) {
            case AUDIO -> "音频";
            case SCORE -> "谱子";
            default -> kind;
        };
    }
}
