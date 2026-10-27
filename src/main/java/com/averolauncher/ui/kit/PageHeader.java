package com.averolauncher.ui.kit;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The compact header every page uses instead of the old banner + icon-square + title block:
 * slash mark, condensed title, optional one-line subtitle, and a slot on the right for page-level
 * actions. About 64 px tall.
 */
public final class PageHeader extends HBox {

    private final HBox actions = new HBox();

    public PageHeader(String title, String subtitle) {
        getStyleClass().add("av-page-header");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(14);

        Label t = new Label(title);
        t.getStyleClass().add("av-page-title");
        VBox text = new VBox(2, t);
        if (subtitle != null && !subtitle.isBlank()) {
            Label s = new Label(subtitle);
            s.getStyleClass().add("av-page-sub");
            s.setWrapText(true);
            s.setMaxWidth(720);
            text.getChildren().add(s);
        }

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        actions.getStyleClass().add("gap-8");
        actions.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(Slash.mark(10, 30), text, spring, actions);
    }

    public PageHeader actions(Node... nodes) {
        actions.getChildren().setAll(nodes);
        return this;
    }
}
