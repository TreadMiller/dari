package com.psddev.dari.db;

import com.psddev.dari.util.ObjectUtils;
import com.psddev.dari.util.Settings;
import com.psddev.dari.util.SettingsException;

import org.slf4j.Logger;

/** Represents an application. */
@Record.Abstract
public class Application extends Record {

    /** Specifies the class name used to determine the main application. */
    public static final String MAIN_CLASS_SETTING = "dari/mainApplicationClass";

    @Indexed
    private String name;

    private String url;

    /** Returns the name. */
    public String getName() {
        return name;
    }

    /** Sets the name. */
    public void setName(String name) {
        this.name = name;
    }

    /** Returns the URL. */
    public String getUrl() {
        return url;
    }

    /** Sets the URL. */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Initializes the application, and writes any messages to the given
     * {@code logger}. By default, this method does nothing, so the
     * subclasses are expected to override it to provide the desired behavior.
     */
    public void initialize(Logger logger) throws Exception {
    }

    /** {@linkplain Application Application} utility methods. */
    public final static class Static {

        private Static() {
        }

        /**
         * Returns the singleton application object matching the given
         * {@code applicationClass} within the given {@code database}.
         */
        @SuppressWarnings("unchecked")
        public static <T extends Application> T getInstanceUsing(
                Class<T> applicationClass,
                Database database) {

            ObjectType type = database.getEnvironment().getTypeByClass(applicationClass);
            T app = Query.from(applicationClass).using(database).first();

            if (app == null) {
                DistributedLock lock = DistributedLock.Static.getInstance(database, applicationClass.getName());
                lock.lock();

                try {
                    app = Query.from(applicationClass).using(database).first();
                    if (app == null) {
                        app = (T) type.createObject(null);
                        app.setName(type.getDisplayName());
                        app.saveImmediately();
                        return app;
                    }

                } finally {
                    lock.unlock();
                }
            }

            String oldName = app.getName();
            String newName = type.getDisplayName();
            if (!ObjectUtils.equals(oldName, newName)) {
                app.setName(newName);
                app.save();
            }

            return app;
        }

        /**
         * Returns the singleton application object matching the given
         * {@code applicationClass} within the {@linkplain
         * Database.Static#getDefault default database}.
         */
        public static <T extends Application> T getInstance(Class<T> applicationClass) {
            return getInstanceUsing(applicationClass, Database.Static.getDefault());
        }

        /**
         * Returns the main application object as determined by the
         * {@value com.psddev.dari.db.Application#MAIN_CLASS_SETTING}
         * {@linkplain Settings#get setting} within the given
         * {@code database}.
         *
         * @return May be {@code null} if the setting is not set.
         * @throws SettingsException If the class name in the setting is not valid.
         */
        public static Application getMainUsing(Database database) {

            String appClassName = Settings.get(String.class, MAIN_CLASS_SETTING);
            if (ObjectUtils.isBlank(appClassName)) {
                return null;
            }

            Class<?> objectClass = ObjectUtils.getClassByName(appClassName);
            if (objectClass == null) {
                throw new SettingsException(MAIN_CLASS_SETTING, String.format(
                        "[%s] is not a valid class name!", appClassName));
            }

            if (Application.class.isAssignableFrom(objectClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends Application> appClass = (Class<? extends Application>) objectClass;
                return getInstanceUsing(appClass, database);

            } else {
                throw new SettingsException(MAIN_CLASS_SETTING, String.format(
                        "[%s] is not a [%s] class!", appClassName,
                        Application.class.getName()));
            }
        }

        /**
         * Returns the main application object as determined by the
         * {@value com.psddev.dari.db.Application#MAIN_CLASS_SETTING}
         * {@linkplain Settings#get setting} within the
         * {@linkplain Database.Static#getDefault default database}.
         *
         * @return May be {@code null} if the setting is not set.
         * @throws SettingsException If the class name in the setting is not valid.
         */
        public static Application getMain() {
            return getMainUsing(Database.Static.getDefault());
        }
    }
}
