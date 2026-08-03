package com.example.signer.so;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

public final class ButtonIcons {

    private static final String FOLDER =
            "M2 5h8l2 2h10v12H2z M4 9v8h16V9z";
    private static final String CONVERT =
            "M7 7h10l-3-3 1.5-1.5L21 8l-5.5 5.5L14 12l3-3H7z "
                    + "M17 17H7l3 3-1.5 1.5L3 16l5.5-5.5L10 12l-3 3h10z";
    private static final String SETTINGS =
            "M19.4 13a7.8 7.8 0 000-2l2-1.5-2-3.5-2.4 1a8 8 0 00-1.7-1L15 3.5h-4L10.7 6a8 8 0 00-1.7 1L6.6 6 4.6 9.5 6.6 11a7.8 7.8 0 000 2l-2 1.5L6.6 18 9 17a8 8 0 001.7 1l.3 2.5h4l.3-2.5a8 8 0 001.7-1l2.4 1 2-3.5z "
                    + "M13 15.5a3.5 3.5 0 110-7 3.5 3.5 0 010 7z";
    private static final String REMOVE =
            "M12 2a10 10 0 100 20 10 10 0 000-20z M7 11h10v2H7z";
    private static final String CLEAR =
            "M7 7h10l-1 14H8z M9 3h6l1 2h4v2H4V5h4z";

    private ButtonIcons() {
    }

    public static Node folder() {
        return create(FOLDER);
    }

    public static Node convert() {
        return create(CONVERT);
    }

    public static Node settings() {
        return create(SETTINGS);
    }

    public static Node remove() {
        return create(REMOVE);
    }

    public static Node clear() {
        return create(CLEAR);
    }

    private static Node create(String content) {
        SVGPath icon = new SVGPath();
        icon.setContent(content);
        icon.setScaleX(0.72);
        icon.setScaleY(0.72);
        icon.getStyleClass().add("button-icon");
        return icon;
    }
}
