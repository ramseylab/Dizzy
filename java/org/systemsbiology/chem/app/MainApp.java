package org.systemsbiology.chem.app;
/*
 * Copyright (C) 2003 by Institute for Systems Biology,
 * Seattle, Washington, USA.  All rights reserved.
 * 
 * This source code is distributed under the GNU Lesser 
 * General Public License, the text of which is available at:
 *   http://www.gnu.org/copyleft/lesser.html
 */

import org.systemsbiology.util.*;
import org.systemsbiology.chem.*;
import org.systemsbiology.gui.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;

public class MainApp
{
    private JFrame mMainFrame;
    private static MainApp mApp;
    private MainMenu mMainMenu;
    private SimulationLauncher mSimulationLauncher;
    private AppConfig mAppConfig;
    private ClassRegistry mModelBuilderRegistry;
    private ClassRegistry mModelExporterRegistry;
    private ClassRegistry mModelViewerRegistry;
    private EditorPane mEditorPane;
    private int mOriginalWidthPixels;
    private int mOriginalHeightPixels;
    private Long mTimestampModelLastLoaded;
    private static final String DEFAULT_HELP_SET_VIEW = "TOC";  // this string must correspond to
                                                                // a <view> block in AppHelp.hs
    private HelpBrowser mHelpBrowser;
    
    static final String UNEXPECTED_ERROR_MESSAGE = "an unexpected error has occurred";
    private File mCurrentDirectory;

    public ClassRegistry getModelViewerRegistry()
    {
        return mModelViewerRegistry;
    }
    
    void setCurrentDirectory(File pCurrentDirectory)
    {
        mCurrentDirectory = pCurrentDirectory;
    }

    File getCurrentDirectory()
    {
        return(mCurrentDirectory);
    }

    private void setTimestampModelLastLoaded(Long pTimestampModelLastLoaded)
    {
        mTimestampModelLastLoaded = pTimestampModelLastLoaded;
    }

    private Long getTimestampModelLastLoaded()
    {
        return(mTimestampModelLastLoaded);
    }

    EditorPane getEditorPane()
    {
        return(mEditorPane);
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

    void setModelViewerRegistry(ClassRegistry pModelViewerRegistry)
    {
        mModelViewerRegistry = pModelViewerRegistry;
    }
    
    void setModelExporterRegistry(ClassRegistry pModelExporterRegistry)
    {
        mModelExporterRegistry = pModelExporterRegistry;
    }


    private void setAppConfig(AppConfig pAppConfig)
    {
        mAppConfig = pAppConfig;
    }

    AppConfig getAppConfig()
    {
        return(mAppConfig);
    }

    void setSimulationLauncher(SimulationLauncher pSimulationLauncher)
    {
        mSimulationLauncher = pSimulationLauncher;
    }
    
    SimulationLauncher getSimulationLauncher()
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
        if(null == mHelpBrowser)
        {
            String helpSetName = mAppConfig.getAppHelpSetName();        
            String appName = mAppConfig.getAppName();
            mHelpBrowser = new HelpBrowser(getMainFrame(), helpSetName, appName);
        }
        mHelpBrowser.displayHelpBrowser(null,
                                        DEFAULT_HELP_SET_VIEW);
    }

    void handleSimulate()
    {
        try
        {
            String appName = getName();
            Model model = mEditorPane.processModel();
            if(null != model)
            {
                enableMenuItem(MainMenu.MenuItem.TOOLS_SIMULATE, false);
                boolean handleOutputInternally = true;
                SimulationLauncher simulationLauncher = new SimulationLauncher(appName, model, handleOutputInternally);
                setSimulationLauncher(simulationLauncher);
                simulationLauncher.setCurrentDirectory(getCurrentDirectory());
                setTimestampModelLastLoaded(new Long(System.currentTimeMillis()));
                simulationLauncher.addListener(new SimulationLauncher.Listener()
                {
                    public void simulationLauncherClosing()
                    {
                        setSimulationLauncher(null);
                        setTimestampModelLastLoaded(null);
                        updateMenus();
                    }
                    public void simulationStarting()
                    {
                        updateMenus();
                    }
                    public void simulationEnding()
                    {
                        updateMenus();
                    }
                });
                updateMenus();
            }
        }
        catch(Throwable e)
        {
            ExceptionNotificationOptionPane errorOptionPane = new ExceptionNotificationOptionPane(e, "Sorry, there was an error loading the simulation launcher window.  The specific error message is:");
            setTimestampModelLastLoaded(null);
            setSimulationLauncher(null);
            updateMenus();
            errorOptionPane.createDialog(mMainFrame, "unable to simulate the model: " + e.getMessage()).show();
        }
    }

    void handleExport(String pAlias)
    {
        try
        {
            Model model = mEditorPane.processModel();

            if(null != model)
            {
                ModelExporter exporter = new ModelExporter((Component) getMainFrame());
                exporter.exportModel(pAlias, model, mModelExporterRegistry);
            }
        }

        catch(Throwable e)
        {
            ExceptionNotificationOptionPane errorOptionPane = new ExceptionNotificationOptionPane(e, "Sorry, there was an error message exporting the model.  The specific error message is:");
            errorOptionPane.createDialog(mMainFrame, "unable to export the model: " + e.getMessage()).show();
        }
    }

    public void handleView(String pAlias)
    {
        try
        {
            Model model = mEditorPane.processModel();
            if(null != model)
            {
                IModelViewer modelViewer = (IModelViewer) mModelViewerRegistry.getInstance(pAlias);
                modelViewer.viewModel(model, mApp.getName());
            }
        }
        catch(Throwable e)
        {
            ExceptionNotificationOptionPane errorOptionPane = new ExceptionNotificationOptionPane(e, "Sorry, there was an error viewing the model.  The specific error message is:");
            errorOptionPane.createDialog(mMainFrame, "unable to view the model: " + e.getMessage()).show();
        }
    }

    private void loadModel()
    {
        Model model = mEditorPane.processModel();

        if(null != model)
        {

            SimulationLauncher simulationLauncher = getSimulationLauncher();
            if(null == simulationLauncher)
            {
                throw new IllegalStateException("simulation launcher window does not exist; cannot reload model");
            }
            SimulationLauncher.SetModelResult result = simulationLauncher.setModel(model);
            if(result == SimulationLauncher.SetModelResult.FAILED_RUNNING)
            {
                JOptionPane.showMessageDialog(mMainFrame,
                                              "Sorry, the model cannot be reloaded while a simulation is running.  Please wait for the simulation to complete and then try again.",
                                              "unable to reload model",
                                              JOptionPane.INFORMATION_MESSAGE);

            }
            else if(result == SimulationLauncher.SetModelResult.FAILED_CLOSED)
            {
                throw new IllegalStateException("unexpected condition:  setModel() called after the simulation launcher has closed");
            }
            else if(result == SimulationLauncher.SetModelResult.SUCCESS)
            {
                simulationLauncher.toFront();
                setTimestampModelLastLoaded(new Long(System.currentTimeMillis()));
                updateMenus();
            }
            else
            {
                throw new IllegalStateException("unexpected condition:  unknown result returned from setModel(); result is: " + result.toString());
            }
        }
    }
    
    void handleReload()
    {
        try
        {
            loadModel();
        }
        catch(Exception e)
        {
            ExceptionNotificationOptionPane optionPane = new ExceptionNotificationOptionPane(e, "Sorry, there was an error reloading the model.  The specific error message is:");
            optionPane.createDialog(mMainFrame, "unable to reload the model").show();
        }
    }



    private void enableMenu(MainMenu.Menu pMenu, boolean pEnabled)
    {
        try
        {
            mMainMenu.setEnabledFlag(pMenu, pEnabled);
        }
        catch(DataNotFoundException e)
        {
            throw new IllegalStateException("could not find menu " + pMenu.toString());
        }
    }

    private void enableMenuItem(MainMenu.MenuItem pMenuItem, boolean pEnabled)
    {
        try
        {
            mMainMenu.setEnabledFlag(pMenuItem, pEnabled);
        }
        catch(DataNotFoundException e)
        {
            throw new IllegalStateException("could not find menu item " + pMenuItem.toString());
        }
    }


    String getName()
    {
        return(getAppConfig().getAppName());
    }

    private Container createComponents()
    {
        JPanel mainPane = new JPanel();

        EditorPane editorPane = new EditorPane(mainPane);
        mEditorPane = editorPane;

        return(mainPane);
    }

    private void initializeMainMenu(JFrame pFrame) throws DataNotFoundException
    {
        MainMenu mainMenu = new MainMenu(this);
        mMainMenu = mainMenu;
        pFrame.setJMenuBar(mainMenu);
    }

    void updateMenus()
    {
        EditorPane editorPane = mEditorPane;
        boolean bufferDirty = editorPane.getBufferDirty();
        boolean bufferEmpty = editorPane.editorBufferIsEmpty();
        long timestampLastChange = editorPane.getTimestampLastChange();
        String bufferFilename = editorPane.getFileName();
        SimulationLauncher simulationLauncher = getSimulationLauncher();
        Long timestampModelLastLoaded = getTimestampModelLastLoaded();

        assert (null == simulationLauncher || null != timestampModelLastLoaded) : "invalid state of mSimulatinoLauncher and mTimesetampModelLastLoaded";
                
        assert (bufferEmpty || timestampLastChange != EditorPane.TIMESTAMP_BUFFER_LAST_CHANGE_NULL) : "invalid state of mEditorPane.editorBufferIsEmpty() and mEditorPane.getTimestampLastChange()";

        boolean simulationRunning = false;
        if(null != simulationLauncher)
        {
            simulationRunning = simulationLauncher.getSimulationInProgress();
        }

        enableMenuItem(MainMenu.MenuItem.FILE_CLOSE, ! bufferEmpty || (null != bufferFilename));
        enableMenuItem(MainMenu.MenuItem.FILE_SAVE_AS, ! bufferEmpty);
        enableMenuItem(MainMenu.MenuItem.FILE_SAVE, bufferDirty && (null != bufferFilename));
        enableMenuItem(MainMenu.MenuItem.TOOLS_VIEW, ! bufferEmpty);
        enableMenuItem(MainMenu.MenuItem.TOOLS_EXPORT, ! bufferEmpty);
        enableMenuItem(MainMenu.MenuItem.TOOLS_SIMULATE, ! bufferEmpty && null == simulationLauncher);
        enableMenuItem(MainMenu.MenuItem.TOOLS_RELOAD, ! bufferEmpty && null != simulationLauncher &&
                                                         timestampLastChange > 
                                                             timestampModelLastLoaded.longValue() &&
                                                       ! simulationRunning);
    }


    private void initializeMainFrame() throws DataNotFoundException
    {
        JFrame frame = new JFrame(getName());
        setMainFrame(frame);

        initializeMainMenu(frame);

        Container mainPane = createComponents();
        frame.setContentPane(mainPane);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();
        
        FramePlacer.placeInCenterOfScreen(frame);
        mOriginalWidthPixels = frameWidth;
        mOriginalHeightPixels = frameHeight;
        
        frame.addComponentListener(new ComponentAdapter()
        {
            public void componentResized(ComponentEvent e)
            {
                int heightPixels = mMainFrame.getHeight();
                int widthPixels = mMainFrame.getWidth();
                int changeWidthPixels = widthPixels - mOriginalWidthPixels;
                int changeHeightPixels = heightPixels - mOriginalHeightPixels;
                mEditorPane.handleResize(widthPixels - mOriginalWidthPixels,
                                         heightPixels - mOriginalHeightPixels);
                mMainFrame.show();  // this is needed to work around a bug on the Windows platform
            }
        });
        updateMenus();
        frame.setVisible(true);
    }

    public MainApp(String []pArgs) throws IllegalStateException, ClassNotFoundException, IOException, DataNotFoundException, InvalidInputException
    {
        if(null != mApp)
        {
            throw new IllegalStateException("only one instance of MainApp can exist at a time");
        }
        mApp = this;
        mHelpBrowser = null;
        
        String appDir = null;
        if(pArgs.length > 0)
        {
            appDir = FileUtils.fixWindowsCommandLineDirectoryNameMangling(pArgs[0]);
        }

        setAppConfig(AppConfig.get(MainApp.class, appDir));
        
        ClassRegistry modelBuilderRegistry = new ClassRegistry(org.systemsbiology.chem.IModelBuilder.class);
        modelBuilderRegistry.buildRegistry();
        setModelBuilderRegistry(modelBuilderRegistry);

        ClassRegistry modelExporterRegistry = new ClassRegistry(org.systemsbiology.chem.IModelExporter.class);
        modelExporterRegistry.buildRegistry();
        setModelExporterRegistry(modelExporterRegistry);

        ClassRegistry modelViewerRegistry = new ClassRegistry(org.systemsbiology.chem.IModelViewer.class);
        modelViewerRegistry.buildRegistry();
        setModelViewerRegistry(modelViewerRegistry);
        
        setSimulationLauncher(null);
        setTimestampModelLastLoaded(null);
        setCurrentDirectory(null);

        initializeMainFrame();
    }



    public static MainApp getApp()
    {
        return(mApp);
    }

    public static final void main(String []pArgs)
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
