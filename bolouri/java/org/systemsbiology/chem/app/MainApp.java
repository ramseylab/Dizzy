package org.systemsbiology.chem.app;

import java.util.*;
import org.systemsbiology.util.*;
import org.systemsbiology.chem.scripting.*;
import org.systemsbiology.chem.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.net.*;
import java.io.File;
import java.io.FileNotFoundException;

public class MainApp
{
    private JFrame mMainFrame;
    private static MainApp mApp;
    private MainMenu mMainMenu;
    private SimulationLauncher mSimulationLauncher;
    private AppConfig mAppConfig;
    private File mAppDir;
    private ClassRegistry mModelBuilderRegistry;
    private ClassRegistry mModelExporterRegistry;
    private EditorPane mEditorPane;

    EditorPane getEditorPane()
    {
        return(mEditorPane);
    }

    File getAppDir()
    {
        return(mAppDir);
    }

    ClassRegistry getModelBuilderRegistry()
    {
        return(mModelBuilderRegistry);
    }

    void setModelBuilderRegistry(ClassRegistry pModelBuilderRegistry)
    {
        mModelBuilderRegistry = pModelBuilderRegistry;
    }

    ClassRegistry getModelExporterRegistry()
    {
        return(mModelExporterRegistry);
    }

    void setModelExporterRegistry(ClassRegistry pModelExporterRegistry)
    {
        mModelExporterRegistry = pModelExporterRegistry;
    }


    private void setAppDir(File pAppDir)
    {
        mAppDir = pAppDir;
    }

    private void setAppConfig(AppConfig pAppConfig)
    {
        mAppConfig = pAppConfig;
    }

    AppConfig getAppConfig()
    {
        return(mAppConfig);
    }

    void setSimulator(SimulationLauncher pSimulationLauncher)
    {
        mSimulationLauncher = pSimulationLauncher;
    }
    
    SimulationLauncher getSimulator()
    {
        return(mSimulationLauncher);
    }

    private void setMainFrame(JFrame pMainFrame)
    {
        mMainFrame = pMainFrame;
    }

    JFrame getMainFrame()
    {
        return(mMainFrame);
    }

    void handleQuit()
    {
        System.exit(0);
    }

    void handleAbout()
    {
        AboutDialog aboutDialog = new AboutDialog(getMainFrame());
        aboutDialog.show();
    }

    void handleHelpBrowser()
    {
        HelpBrowser helpBrowser = new HelpBrowser(getMainFrame());
        helpBrowser.displayHelpBrowser();
    }

    void handleExport()
    {
        Model model = mEditorPane.processModel();
        ModelExporter exporter = new ModelExporter(getMainFrame());
        exporter.exportModel(model, mModelExporterRegistry);
    }

    void handleSimulate()
    {
        try
        {
            String appName = mAppConfig.getAppName();
            Model model = mEditorPane.processModel();
            SimulationLauncher simulator = new SimulationLauncher(appName, model, this);
        }
        catch(Exception e)
        {
            UnexpectedErrorDialog errorDialog = new UnexpectedErrorDialog(mMainFrame, "unable to create the simulation launcher window");
            enableSimulateMenuItem(true);
            errorDialog.show();            
        }
    }

    void enableExportMenuItem(boolean pEnabled)
    {
        boolean enabled = false;
        if(pEnabled)
        {
            if(! mEditorPane.editorBufferIsEmpty())
            {
                enabled = true;
            }
        }
        mMainMenu.getExportMenuItem().setEnabled(enabled);
    }

    void enableSaveMenuItem(boolean pEnabled)
    {
        mMainMenu.getSaveMenuItem().setEnabled(pEnabled);
    }

    void enableCloseMenuItem(boolean pEnabled)
    {
        mMainMenu.getCloseMenuItem().setEnabled(pEnabled);
    }
    
    void enableSimulateMenuItem(boolean pEnabled)
    {
        boolean enabled = false;
        if(pEnabled)
        {
            if(! mEditorPane.editorBufferIsEmpty())
            {
                enabled = true;
            }
        }
        mMainMenu.getSimulateMenuItem().setEnabled(enabled);
    }


    private void initializeAppConfig(String pAppDir) throws DataNotFoundException, InvalidInputException, FileNotFoundException
    {
        
        AppConfig appConfig = null;
        if(null == pAppDir)
        {
            // we don't know where we are installed, so punt and look for 
            // the config file as a class resource:
            appConfig = new AppConfig(MainApp.class);
        }
        else
        {
            File appDirFile = new File(pAppDir);
            if(! appDirFile.exists() ||
               ! appDirFile.isDirectory())
            {
                throw new DataNotFoundException("could not find application directory: " + pAppDir);
            }
            setAppDir(appDirFile);
            String configFileName = appDirFile.getAbsolutePath() + "/config/" + AppConfig.CONFIG_FILE_NAME;
            appConfig = new AppConfig(new File(configFileName));
        }
        setAppConfig(appConfig);
    }

    private Container createComponents()
    {
        JPanel mainPane = new JPanel();
        LayoutManager layoutManager = new BoxLayout(mainPane, BoxLayout.Y_AXIS);
        mainPane.setLayout(layoutManager);

        EditorPane editorPane = new EditorPane(mainPane);
        mEditorPane = editorPane;

        return(mainPane);
    }

    private void initializeMainFrame()
    {
        JFrame frame = new JFrame(getAppConfig().getAppName());
        setMainFrame(frame);
        MainMenu mainMenu = new MainMenu(this);
        frame.setJMenuBar(mainMenu);
        mMainMenu = mainMenu;
        Container mainPane = createComponents();
        frame.setContentPane(mainPane);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension frameSize = frame.getSize();
        frame.setLocation((screenSize.width - frameSize.width) / 2,
                          (screenSize.height - frameSize.height) / 2);

        frame.setVisible(true);
    }

    public MainApp(String []pArgs) throws IllegalStateException, ClassNotFoundException, IOException, DataNotFoundException, InvalidInputException
    {
        if(null != mApp)
        {
            throw new IllegalStateException("only one instance of MainApp can exist at a time");
        }
        mApp = this;
        
        String appDir = null;
        if(pArgs.length > 0)
        {
            // argument is the main application directory
            appDir = pArgs[0];
        }

        initializeAppConfig(appDir);

        ClassRegistry modelBuilderRegistry = new ClassRegistry(org.systemsbiology.chem.scripting.IModelBuilder.class);
        modelBuilderRegistry.buildRegistry();
        setModelBuilderRegistry(modelBuilderRegistry);

        ClassRegistry modelExporterRegistry = new ClassRegistry(org.systemsbiology.chem.scripting.IModelExporter.class);
        modelExporterRegistry.buildRegistry();
        setModelExporterRegistry(modelExporterRegistry);

        initializeMainFrame();
    }

    public static MainApp getApp()
    {
        return(mApp);
    }

    public void exit(int pCode)
    {
        System.exit(pCode);
    }

    public static void main(String []pArgs)
    {
        try
        {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            MainApp app = new MainApp(pArgs);
        }

        catch(Exception e)
        {
            e.printStackTrace(System.err);
        }
    }
}