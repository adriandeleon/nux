package com.nux.ui;

import static com.nux.i18n.Messages.tr;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.nux.AppInfo;

/** About: what this build is, who wrote it, its license, and where to find it. */
public final class AboutWindow {

    private AboutWindow() {}

    /** {@code openUrl} is the host-services opener; {@code configDir} appears in the copyable details. */
    public static void show(Window owner, Consumer<String> openUrl, String configDir) {
        VBox header = new VBox(2, title(), version(), subtitle(tr("about.tagline")));
        header.setAlignment(Pos.CENTER_LEFT);

        HBox top = new HBox(16, icon(), header);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox links = new VBox(
                2,
                subtitle(AppInfo.COPYRIGHT),
                link(tr("about.license"), AppInfo.LICENSE_URL, openUrl),
                link(tr("about.source"), AppInfo.REPOSITORY, openUrl),
                link(tr("about.issues"), AppInfo.ISSUES_URL, openUrl),
                link(tr("about.nut"), AppInfo.NUT_URL, openUrl));

        Label details = new Label(AppInfo.supportSummary(configDir));
        details.getStyleClass().add("about-details");
        details.setWrapText(true);

        Stage stage = new Stage();
        Button copy = new Button(tr("about.copyDetails"));
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(AppInfo.supportSummary(configDir));
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText(tr("about.copied"));
        });
        Button close = new Button(tr("about.close"));
        close.setDefaultButton(true);
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, copy, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(14, top, links, details, buttons);
        root.setPadding(new Insets(20));

        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setResizable(false);
        stage.setTitle(tr("toolbar.about", AppInfo.NAME));
        Scene scene = new Scene(root, 440, 400);
        scene.getStylesheets().add(AboutWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private static ImageView icon() {
        var url = AboutWindow.class.getResource("/com/nux/icons/nux-128.png");
        ImageView view = new ImageView(url == null ? null : new Image(url.toExternalForm()));
        view.setFitWidth(72);
        view.setFitHeight(72);
        view.setPreserveRatio(true);
        return view;
    }

    private static Label title() {
        Label label = new Label(AppInfo.NAME);
        label.getStyleClass().add("about-title");
        return label;
    }

    private static Label version() {
        // A snapshot build says so, so a dev copy is never mistaken for a release.
        String text = tr("about.version", AppInfo.VERSION) + (AppInfo.isSnapshot() ? tr("about.development") : "");
        Label label = new Label(text);
        label.getStyleClass().add("detail-name");
        return label;
    }

    private static Label subtitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("detail-name");
        return label;
    }

    private static Hyperlink link(String text, String url, Consumer<String> openUrl) {
        Hyperlink hyperlink = new Hyperlink(text);
        hyperlink.setTooltip(new javafx.scene.control.Tooltip(url));
        hyperlink.setOnAction(e -> openUrl.accept(url));
        return hyperlink;
    }
}
