package com.uddernetworks.mspaint.main;

import com.uddernetworks.mspaint.code.GeneralRunningCodeManager;
import com.uddernetworks.mspaint.code.ImageClass;
import com.uddernetworks.mspaint.code.RunningCodeManager;
import com.uddernetworks.mspaint.code.highlighter.AngrySquiggleHighlighter;
import com.uddernetworks.mspaint.code.languages.Language;
import com.uddernetworks.mspaint.code.languages.LanguageError;
import com.uddernetworks.mspaint.code.languages.LanguageManager;
import com.uddernetworks.mspaint.code.languages.brainfuck.BrainfuckLanguage;
import com.uddernetworks.mspaint.code.languages.java.JavaLanguage;
import com.uddernetworks.mspaint.code.languages.python.PythonLanguage;
import com.uddernetworks.mspaint.imagestreams.ImageOutputStream;
import com.uddernetworks.mspaint.painthook.InjectionManager;
import com.uddernetworks.mspaint.project.PPFProject;
import com.uddernetworks.mspaint.project.ProjectManager;
import com.uddernetworks.mspaint.settings.Setting;
import com.uddernetworks.mspaint.settings.SettingsManager;
import com.uddernetworks.newocr.OCRHandle;
import com.uddernetworks.newocr.database.DatabaseManager;
import com.uddernetworks.newocr.database.OCRDatabaseManager;
import org.apache.batik.transcoder.TranscoderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class Main {

    private static Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private File parent;
    private File currentJar;

    private MainGUI mainGUI;

    private List<ImageClass> imageClasses = new ArrayList<>();

    private LanguageManager languageManager = new LanguageManager();
    private Language currentLanguage;
    private DatabaseManager databaseManager;
    private RunningCodeManager runningCodeManager;
    private OCRHandle ocrHandle;
    private boolean usingInternal;

    public void start(MainGUI mainGUI) throws IOException, URISyntaxException {
        headlessStart();
        this.mainGUI = mainGUI;
        currentJar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
        parent = currentJar.getParentFile();

        this.mainGUI.setDarkTheme(SettingsManager.getSetting(Setting.DARK_THEME, Boolean.class));
        this.mainGUI.updateTheme();

        Splash.setStatus("Adding languages...");

        languageManager.addLanguage(new JavaLanguage());
        languageManager.addLanguage(new BrainfuckLanguage());
        languageManager.addLanguage(new PythonLanguage());

        languageManager.initializeLanguages();
        mainGUI.addLanguages(languageManager.getEnabledLanguages());

        this.runningCodeManager = new GeneralRunningCodeManager(this);

        new InjectionManager(mainGUI, this).createHooks();
    }

    public void headlessStart() throws IOException {
        Splash.setStatus("Loading settings...");
        SettingsManager.initialize(new File(MainGUI.APP_DATA, "options.ini"));

        Splash.setStatus("Loading database...");
        SettingsManager.onChangeSetting(Setting.DATABASE_USE_INTERNAL, useInternal -> {
            try {
                if (useInternal == this.usingInternal) return;
                this.usingInternal = useInternal;

                if (this.databaseManager != null) this.databaseManager.shutdown();

                if (useInternal) {
                    String location = SettingsManager.getSetting(Setting.DATABASE_INTERNAL_LOCATION, String.class);
                    File file = location != null && !location.trim().equals("") ? new File(location) : null;

                    if (file == null || (!file.isDirectory() && !file.mkdirs())) {
                        LOGGER.error("Invalid/unset internal database location");
                        return;
                    }

                    this.databaseManager = new OCRDatabaseManager(new File(file, "ocr_db"));
                } else {
                    String url = SettingsManager.getSetting(Setting.DATABASE_URL, String.class);
                    String user = SettingsManager.getSetting(Setting.DATABASE_USER, String.class);
                    String pass = SettingsManager.getSetting(Setting.DATABASE_PASS, String.class);

                    if (url == null || user == null || pass == null || url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                        LOGGER.error("Couldn't set up database manager, partial/missing credentials in settings.");
                        return;
                    }

                    this.databaseManager = new OCRDatabaseManager(url, user, pass);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, Boolean.class, true);
    }

    public void setCurrentLanguage(Language language) {
        this.currentLanguage = language;
    }

    public Language getCurrentLanguage() {
        return this.currentLanguage;
    }

    private boolean optionsNotFilled() {
        PPFProject ppfProject = ProjectManager.getPPFProject();
        return ppfProject.getInputLocation() == null || ppfProject.getClassLocation() == null || (getCurrentLanguage().getOutputFileExtension() != null && ppfProject.getCompilerOutput() == null);
    }

    public int indexAll(boolean useCaches, boolean saveCaches) {
        if (optionsNotFilled()) {
            LOGGER.error("Please select files for all options");
            mainGUI.setHaveError();
            return -1;
        }

        LOGGER.info("Scanning all images...");
        long start = System.currentTimeMillis();

        mainGUI.setStatusText(null);

        File inputImage = ProjectManager.getPPFProject().getInputLocation();

        if (inputImage.isDirectory()) {
            LOGGER.info("Found directory: " + inputImage.getAbsolutePath());
            for (File imageFile : getFilesFromDirectory(inputImage, this.currentLanguage.getFileExtensions(), "png")) {
                LOGGER.info("Adding non directory: " + imageFile.getAbsolutePath());
                imageClasses.add(new ImageClass(imageFile, mainGUI, true, useCaches, saveCaches));
            }
        } else {
            LOGGER.info("Adding non directory: " + inputImage.getAbsolutePath());
            imageClasses.add(new ImageClass(inputImage, mainGUI, true, useCaches, saveCaches));
        }

        mainGUI.setStatusText(null);

        LOGGER.info("Finished scanning all images in " + (System.currentTimeMillis() - start) + "ms");
        return 1;
    }

    public void highlightAll() throws IOException {
        if (optionsNotFilled()) {
            LOGGER.error("Please select files for all options");
            mainGUI.setHaveError();
            return;
        }

        File highlightedFile = ProjectManager.getPPFProject().getHighlightLocation();

        if (highlightedFile != null && !highlightedFile.isDirectory()) highlightedFile.mkdirs();

        if (highlightedFile == null || !highlightedFile.isDirectory()) {
            LOGGER.error("No highlighted file directory found!");
            mainGUI.setHaveError();
            return;
        }

        LOGGER.info("Scanning all images...");
        mainGUI.setStatusText("Highlighting...");
        mainGUI.setIndeterminate(true);
        long start = System.currentTimeMillis();

        for (ImageClass imageClass : imageClasses) {
            imageClass.highlight(highlightedFile);
        }

        mainGUI.setIndeterminate(false);
        mainGUI.setStatusText(null);

        LOGGER.info("Finished highlighting all images in " + (System.currentTimeMillis() - start) + "ms");
    }


    public void compile(boolean execute) throws IOException {
        long start = System.currentTimeMillis();

        if (getCurrentLanguage().isInterpreted()) {
            LOGGER.info("Interpreting...");
            mainGUI.setStatusText("Interpreting...");
        } else {
            LOGGER.info("Compiling...");
            mainGUI.setStatusText("Compiling...");
        }

        File libraryFile = ProjectManager.getPPFProject().getLibraryLocation();

        mainGUI.setIndeterminate(true);

        List<File> libFiles = new ArrayList<>();
        if (libraryFile != null) {
            if (libraryFile.isFile()) {
                if (libraryFile.getName().endsWith(".jar")) {
                    libFiles.add(libraryFile);
                }
            } else {
                libFiles.addAll(getFilesFromDirectory(libraryFile, "jar"));
            }
        }

        ImageOutputStream imageOutputStream = new ImageOutputStream(ProjectManager.getPPFProject().getAppOutput(), 500);
        ImageOutputStream compilerOutputStream = new ImageOutputStream(ProjectManager.getPPFProject().getCompilerOutput(), 500);
        Map<ImageClass, List<LanguageError>> errors = null;

        try {
            errors = getCurrentLanguage().compileAndExecute(imageClasses, ProjectManager.getPPFProject().getJarFile(), ProjectManager.getPPFProject().getOtherLocation(), ProjectManager.getPPFProject().getClassLocation(), mainGUI, imageOutputStream, compilerOutputStream, libFiles, execute);

            LOGGER.info("Highlighting Angry Squiggles...");
            mainGUI.setStatusText("Highlighting Angry Squiggles...");

            for (ImageClass imageClass : errors.keySet()) {
                AngrySquiggleHighlighter highlighter = new AngrySquiggleHighlighter(mainGUI.getMain(), imageClass, 3, imageClass.getHighlightedFile(), imageClass.getScannedImage(), errors.get(imageClass));
                highlighter.highlightAngrySquiggles();
            }

        } catch (TranscoderException | ExecutionException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            Optional<Map.Entry<ImageClass, List<LanguageError>>> firstEntry = errors != null ? errors.entrySet().stream().findFirst() : Optional.empty();
            String append = "";
            if (firstEntry.isPresent()) {
                append += " With ";
                List<LanguageError> languageErrors = firstEntry.get().getValue();
                if (languageErrors.size() > 1) {
                    append += languageErrors.size() + " errors";
                } else {
                    append += "an error (" + languageErrors.get(0).getMessage() + " in " + firstEntry.get().getKey().getInputImage().getPath() + ")";
                }

                append += ". See compiler output image for details";
            }

            LOGGER.info("Finished " + (getCurrentLanguage().isInterpreted() ? "interpreting" : "compiling") + " in " + (System.currentTimeMillis() - start) + "ms" + append);

            LOGGER.info("Saving output images...");
            mainGUI.setStatusText("Saving output images...");

            imageOutputStream.saveImage();
            compilerOutputStream.saveImage();

            mainGUI.setStatusText(null);
        }

        imageClasses.clear();
    }

    public static List<File> getFilesFromDirectory(File directory, String extension) {
        return getFilesFromDirectory(directory, new String[] {extension});
    }

    public static List<File> getFilesFromDirectory(File directory, String[] extensions) {
        List<File> ret = new ArrayList<>();
        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                ret.addAll(getFilesFromDirectory(file, extensions));
            } else {
                if (extensions == null || Arrays.stream(extensions).anyMatch(extension -> file.getName().endsWith("." + extension))) ret.add(file);
            }
        }

        return ret;
    }

    public List<File> getFilesFromDirectory(File directory, String[] extensions, String postExtension) {
        return getFilesFromDirectory(directory, Arrays.stream(extensions).map(string -> string + "." + postExtension).toArray(String[]::new));
    }

    public void setInputImage(File inputImage) {
        PPFProject ppfProject = ProjectManager.getPPFProject();
        if (Objects.equals(inputImage, ppfProject.getInputLocation())) return;
        ProjectManager.getPPFProject().setInputLocation(inputImage);

        File outputParent = inputImage.getParentFile();

        File file = this.currentLanguage.getOutputFileExtension() == null ? null : new File(outputParent, "Output." + this.currentLanguage.getOutputFileExtension());

        ppfProject.setHighlightLocation(new File(outputParent, "highlighted"), false);
        ppfProject.setCompilerOutput(new File(outputParent, "compiler.png"), false);
        ppfProject.setAppOutput(new File(outputParent, "program.png"), false);
        ppfProject.setJarFile(file, false);
        ppfProject.setClassLocation(new File(outputParent, "classes"), false);

        ProjectManager.save();
        this.mainGUI.initializeInputTextFields();
    }

    public LanguageManager getLanguageManager() {
        return this.languageManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public OCRHandle getOCRHandle() {
        if (this.ocrHandle == null) this.ocrHandle = new OCRHandle(this.databaseManager);
        return ocrHandle;
    }

    public RunningCodeManager getRunningCodeManager() {
        return runningCodeManager;
    }
}
