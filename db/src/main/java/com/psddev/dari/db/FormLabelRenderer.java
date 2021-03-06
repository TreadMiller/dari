package com.psddev.dari.db;

import com.psddev.dari.util.HtmlWriter;

import java.io.IOException;

/** Writes input labels for {@link FormWriter}. */
public interface FormLabelRenderer {

    /** Returns an HTML string for displaying an input label. */
    public String display(String inputId, String inputName, ObjectField field);

    /** Default {@link FormLabelRenderer}. */
    public static class Default extends AbstractFormLabelRenderer {

        @Override
        protected void doDisplay(String inputId, String inputName, ObjectField field, HtmlWriter writer) throws IOException {
            writer.start("label", "for", inputId);
                writer.html(field.getDisplayName());
            writer.end();
        }
    }

    // --- Deprecated ---

    /** @deprecated Use {@link AbstractFormLabelRenderer} instead. */
    @Deprecated
    public static abstract class Abstract extends AbstractFormLabelRenderer {
    }
}
