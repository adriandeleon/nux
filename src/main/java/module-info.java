module com.nux {
    requires javafx.controls;
    requires atlantafx.base;

    // javafx.graphics reflectively instantiates the Application subclass.
    exports com.nux to javafx.graphics;
}
