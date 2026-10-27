package com.averolauncher.ui.kit;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Centered "nothing here yet" block: optional icon, a short title, one line of help, one action. */
public final class EmptyState extends VBox {

    private final Label title = new Label();
    private final Label body = new Label();

    public EmptyState(String titleText, String bodyText) {
        getStyleClass().addAll("av-empty", "gap-12", "pad-32");
        setAlignment(Pos.CENTER);
        title.getStyleClass().add("av-empty-title");
        title.setText(titleText);
        body.getStyleClass().add("av-empty-body");
        body.setText(bodyText);
        body.setWrapText(true);
        body.setMaxWidth(420);
        body.setAlignment(Pos.CENTER);
        body.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        getChildren().addAll(title, body);
    }

    public EmptyState icon(Node icon) {
        icon.getStyleClass().add("av-empty-icon");
        getChildren().add(0, icon);
        return this;
    }

    public EmptyState action(String text, Runnable run) {
        AvButton b = new AvButton(text, AvButton.Kind.PRIMARY);
        b.setOnAction(e -> run.run());
        getChildren().add(b);
        return this;
    }
}
