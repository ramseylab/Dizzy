package org.systemsbiology.chem.app;
/*
 * Copyright (C) 2003 by Institute for Systems Biology,
 * Seattle, Washington, USA.  All rights reserved.
 * 
 * This source code is distributed under the GNU Lesser 
 * General Public License, the text of which is available at:
 *   http://www.gnu.org/copyleft/lesser.html
 */

/**
 * Provides a GUI interface for initiating and controlling simulations.
 */
import org.systemsbiology.chem.*;
import org.systemsbiology.data.*;
import org.systemsbiology.util.*;
import org.systemsbiology.math.*;
import org.systemsbiology.gui.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;
import java.io.*;
import java.text.*;

public class SimulationLauncher
{
    private static final int OUTPUT_TEXT_AREA_NUM_ROWS = 20;
    private static final int OUTPUT_TEXT_AREA_NUM_COLS = 40;
    private static final int SYMBOL_LIST_BOX_ROW_COUNT = 12;
    private static final int SIMULATORS_LIST_BOX_ROW_COUNT = 12;
    private static final int OUTPUT_FILE_TEXT_FIELD_SIZE_CHARS = 20;
    private static final int DEFAULT_PROGRESS_BAR_VALUE = 0;
    private static final double MILLISECONDS_PER_SECOND = 1000.0;
    private static final String DEFAULT_SECONDS_REMAINING_NUMBER_TEXT = "";
    private static final int RESULTS_LIST_ROW_COUNT = 5;
    private static final String TOOLTIP_FILE_OUTPUT = "save output to a file in comma-separated-value (CSV) format";
    private static final Double DEFAULT_ERROR_TOLERANCE_RELATIVE = new Double(1e-6);

    private ClassRegistry mSimulatorRegistry;
    private Model mModel;
    private ArrayList mListeners;
    private boolean mHandleOutputInternally;
    
    // simulation runner stuff
    private SimulationRunner mSimulationRunner;
    private SimulationRunParameters mSimulationRunParameters;
    private SimulationController mSimulationController;
    private SimulationProgressReporter mSimulationProgressReporter;
    private SimulationProgressReportHandler mSimulationProgressReportHandler;
    private Queue mResultsQueue;
    private ArrayList mResultsList;
    private SignificantDigitsCalculator mSignificantDigitsCalculator;
    private ScientificNumberFormat mScientificNumberFormat;

    // swing controls for the basic launcher
    private Component mLauncherFrame;
    private JTextField mStartTimeField;
    private JTextField mStopTimeField;
    private JTextField mNumPointsField;
    private JList mSimulatorsList;
    private JList mSymbolList;
    private JTextField mEnsembleField;
    private JLabel mEnsembleFieldLabel;
    private JTextField mStepSizeFractionField;
    private JLabel mStepSizeFractionFieldLabel;
    private JTextField mAllowedRelativeErrorField;
    private JLabel mAllowedRelativeErrorFieldLabel;
    private JTextField mAllowedAbsoluteErrorField;
    private JLabel mAllowedAbsoluteErrorFieldLabel;
    private JTextField mNumHistoryBinsField;
    private JLabel mNumHistoryBinsFieldLabel;
    private JButton mStartButton;
    private JButton mPauseButton;
    private JButton mResumeButton;
    private JButton mCancelButton;
    private String mAppName;
    private JLabel mModelNameLabel;
    private JProgressBar mSimulationProgressBar;
    private JLabel mSecondsRemainingLabel;
    private JTextField mSecondsRemainingNumber;
    private JList mSimulationResultsList;
    private JScrollPane mSimulationResultsListScrollPane;
    private JButton mProcessSimulationResultsButton;

    // variables used for the "output panel", which controls what should be
    // done with the simulation results:
    private OutputType mOutputType;
    private File mOutputFile;
    private JTextField mOutputFileField;
    private JCheckBox mOutputFileAppendCheckBox;
    private JLabel mOutputFileAppendLabel;
    private File mCurrentDirectory;
    private JLabel mOutputFileFormatListLabel;
    private JComboBox mOutputFileFormatList;
    private FramePlacer mFramePlacer;

    static class OutputType
    {
        private final String mName;
        private static HashMap mMap;

        static
        {
            mMap = new HashMap();
        }

        public String toString()
        {
            return(mName);
        }
        private OutputType(String pName)
        {
            mName = pName;
            mMap.put(mName, this);
        }
        public static OutputType get(String pName)
        {
            return((OutputType) mMap.get(pName));
        }

        public static final OutputType TABLE = new OutputType("table");
        public static final OutputType PLOT = new OutputType("plot");
        public static final OutputType FILE = new OutputType("store");
    }

    static class OutputDescriptor
    {
        OutputType mOutputType;
        String mOutputFileName;
        boolean mOutputFileAppend;
        TimeSeriesOutputFormat mOutputFileFormat;
    }

    class SimulationRunParameters
    {
        ISimulator mSimulator;
        String mSimulatorAlias;
        double mStartTime;
        double mEndTime;
        SimulatorParameters mSimulatorParameters;
        int mNumTimePoints;
        OutputDescriptor mOutputDescriptor;
        String []mRequestedSymbolNames;
    }

    // This thread handles updates to the progress bar.  It
    // waits on the SimulationProgressReporter instance monitor,
    // and when it gets notified, it obtains the "fraction complete"
    // information from the reporter, and updates the progess bar
    // UI widget, if necessary.  
    class SimulationProgressReportHandler implements Runnable
    {
        private static final long NULL_TIME_UPDATE_MILLIS = 0;

        private double mLastUpdateFractionComplete;
        private long mLastUpdateTimeMillis;
        private boolean mTerminate;
        private NumberFormat mNumberFormat;

        private synchronized void setLastUpdateTimeMillis(long pLastUpdateTimeMillis)
        {
            mLastUpdateTimeMillis = pLastUpdateTimeMillis;
        }

        private synchronized long getLastUpdateTimeMillis()
        {
            return(mLastUpdateTimeMillis);
        }

        public void clearLastUpdateTime()
        {
            setLastUpdateTimeMillis(NULL_TIME_UPDATE_MILLIS);
        }

        public SimulationProgressReportHandler()
        {
            clearLastUpdateTime();
            mTerminate = false;
            SignificantDigitsCalculator sigCalc = new SignificantDigitsCalculator();
            mNumberFormat = new ScientificNumberFormat(sigCalc);
        }

        public void setTerminate(boolean pTerminate)
        {
            synchronized(this)
            {
                mTerminate = pTerminate;
            }
            synchronized(mSimulationProgressReporter)
            {
                mSimulationProgressReporter.notifyAll();
            }
        }

        private synchronized boolean getTerminate()
        {
            return(mTerminate);
        }

        public void run()
        {
            SimulationProgressReporter reporter = mSimulationProgressReporter;
            while(true)
            {
                synchronized(reporter)
                {
                    reporter.waitForUpdate();
                    if(getTerminate())
                    {
                        return;
                    }
                    boolean simulationFinished = reporter.getSimulationFinished();
                    boolean simulationCancelled = mSimulationController.getCancelled();
                    if(! reporter.getSimulationFinished() && ! mSimulationController.getCancelled())
                    {
                        long updateTimeMillis = reporter.getTimeOfLastUpdateMillis();
                        double fractionComplete = reporter.getFractionComplete();

                        String estimatedTimeToCompletionStr = null;
                        long lastUpdateTimeMillis = getLastUpdateTimeMillis();
                        int percentComplete = (int) (100.0 * fractionComplete);
                        int lastPercentComplete = (int) (100.0 * mLastUpdateFractionComplete);

                        if(NULL_TIME_UPDATE_MILLIS != lastUpdateTimeMillis)
                        {
                            double changeFraction = fractionComplete - mLastUpdateFractionComplete;

                            if(changeFraction > 0.0)
                            {
                                long changeTimeMillis = updateTimeMillis - lastUpdateTimeMillis;
                                double changeTimeSeconds = ((double) changeTimeMillis) / MILLISECONDS_PER_SECOND;
                                double timeToCompletion = (1.0 - fractionComplete) * changeTimeSeconds / changeFraction;
                                estimatedTimeToCompletionStr = mNumberFormat.format(timeToCompletion);
                            }
                            else
                            {
                                estimatedTimeToCompletionStr = "STALLED";
                            }
                        }
                        else
                        {
                            // cannot estimate time to completion, yet
                            estimatedTimeToCompletionStr = "UNKNOWN";

                            mSimulationProgressBar.setValue(DEFAULT_PROGRESS_BAR_VALUE);
                        }

                        // if there has been any change in the percent complete, reset the progress bar
                        if(lastPercentComplete != percentComplete)
                        {
                            mSimulationProgressBar.setValue(percentComplete);
                        }

                        // record the "last update time"
                        setLastUpdateTimeMillis(updateTimeMillis);
                        mSecondsRemainingNumber.setText(estimatedTimeToCompletionStr);
                        mLastUpdateFractionComplete = fractionComplete;
                    }
                    else
                    {
                        clearLastUpdateTime();
                        mLastUpdateFractionComplete = 0.0;
                        mSecondsRemainingNumber.setText(DEFAULT_SECONDS_REMAINING_NUMBER_TEXT);
                        mSimulationProgressBar.setValue(DEFAULT_PROGRESS_BAR_VALUE);
                    }
                }
            }
        }
    }

    // This thread runs the simulation; it waits around for the signal to
    // start doing a simulation.  When the simulation is complete, it waits
    // on the SimulationRunner instance monitor.
    class SimulationRunner implements Runnable
    {
        private boolean mTerminate;
        public SimulationRunner()
        {
            mTerminate = false;
        }

        public synchronized void setTerminate(boolean pTerminate)
        {
            mTerminate = pTerminate;
            this.notifyAll();
        }

        private synchronized boolean getTerminate()
        {
            return(mTerminate);
        }

        public void run()
        {
            while(true)
            {
                SimulationRunParameters simulationRunParameters = getSimulationRunParameters();
                if(null != simulationRunParameters)
                {
                    runSimulation(simulationRunParameters);
                }
                else
                {
                    try
                    {
                        synchronized(this)
                        {
                            this.wait();
                        }
                    }
                    catch(InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                    if(getTerminate())
                    {
                        return;
                    }
                }
            }
        }
    }


    /**
     * Enumerates the possible results of calling {@link #setModel(org.systemsbiology.chem.Model)}.
     */
    public static class SetModelResult
    {
        private final String mName;
        private SetModelResult(String pName)
        {
            mName = pName;
        }

        public static final SetModelResult FAILED_CLOSED = new SetModelResult("failed_closed");
        public static final SetModelResult SUCCESS = new SetModelResult("success");
        public static final SetModelResult FAILED_RUNNING = new SetModelResult("failed_running");
    }

    public interface Listener
    {
        public void simulationLauncherClosing();
        public void simulationStarting();
        public void simulationEnding();
    }

    /**
     * Creates a simulation launcher window in a JFrame.
     *
     * @param pAppName A string that is embedded in the title bar of the launcher frame.  It should
     * be kept short, to ensure that it displays nicely in the launcher frame title bar.
     *
     * @param pModel The {@link org.systemsbiology.chem.Model model} is a required parameter.
     * The model may be changed by calling {@link #setModel(org.systemsbiology.chem.Model)}.
     *
     * @param pHandleOutputInternally Controls whether the launcher should handle the simulation output
     * itself, or delegate that responsibility to the calling application.  If this parameter is "true",
     * the launcher will handle the output internally, and the caller will not have access to the 
     * simulation results in a structured format.  If instead the parameter is "false", the caller will
     * be able to access the simulation results in a structured format by calling {@link #getNextResults()},
     * and the launcher will not handle the simulation results data.
     * 
     */
    public SimulationLauncher(String pAppName,
                              Model pModel,
                              boolean pHandleOutputInternally) throws ClassNotFoundException, IOException, InstantiationException, IllegalStateException
    {
        mAppName = pAppName;

        mHandleOutputInternally = pHandleOutputInternally;

        mSignificantDigitsCalculator = new SignificantDigitsCalculator();
        mScientificNumberFormat = new ScientificNumberFormat(mSignificantDigitsCalculator);

        mListeners = new ArrayList();
        if(mHandleOutputInternally)
        {
            mResultsQueue = null;
            mResultsList = new ArrayList();
            mFramePlacer = new FramePlacer();
        }
        else
        {
            mResultsQueue = new ListQueue();
            mResultsList = null;
            mFramePlacer = null;
        }
        mOutputFile = null;
        setSimulationRunParameters(null);

        createSimulationController();
        createSimulationProgressReporter();
        createSimulatorRegistry();
        createSimulationRunnerThread();
        createSimulationProgressReporterThread();

        // create the launcher frame with all its controls
        createLauncherFrame(JFrame.class);

        // set the model and fill the symbol list-box
        setModel(pModel);

        // The simulator parameters panel has to be updated
        // based on the choice of simulator, and the model.
        updateSimulatorParametersPanel();

        // pack the launcher frame and set its location
        activateLauncherFrame();
    }

    private TimeSeriesOutputFormat getSelectedOutputFileFormat() 
    {
        String outputFileFormatName = (String) mOutputFileFormatList.getSelectedItem();
        return(TimeSeriesOutputFormat.get(outputFileFormatName));
    }

    private void createSimulatorRegistry() throws ClassNotFoundException, IOException
    {
        // create simulator aliases
        ClassRegistry classRegistry = new ClassRegistry(org.systemsbiology.chem.ISimulator.class);
        classRegistry.buildRegistry();
        mSimulatorRegistry = classRegistry;
    }

    private void createSimulationController()
    {
        SimulationController controller = new SimulationController();
        mSimulationController = controller;
    }
    
    private void createSimulationProgressReporter()
    {
        SimulationProgressReporter reporter = new SimulationProgressReporter();
        mSimulationProgressReporter = reporter;
    }

    private void activateLauncherFrame()
    {
        Component frame = getLauncherFrame();

        if(frame instanceof JFrame)
        {
            JFrame myFrame = (JFrame) frame;
            myFrame.pack();
            FramePlacer.placeInCenterOfScreen(myFrame);
        }
        else if(frame instanceof JInternalFrame)
        {
            JInternalFrame myFrame = (JInternalFrame) frame;
            myFrame.pack();
        }
        else
        {
            throw new IllegalStateException("unknown container type");
        }
        
        frame.setVisible(true);
    }

    synchronized Component getLauncherFrame()
    {
        return(mLauncherFrame);
    }

    synchronized void setLauncherFrame(Component pLauncherFrame)
    {
        mLauncherFrame = pLauncherFrame;
    }

    public Set getSimulatorAliasesCopy()
    {
        return(getSimulatorRegistry().getRegistryAliasesCopy());
    }

    private ClassRegistry getSimulatorRegistry()
    {
        return(mSimulatorRegistry);
    }

    private void setSimulatorRegistry(ClassRegistry pSimulatorRegistry)
    {
        mSimulatorRegistry = pSimulatorRegistry;
    }

    private synchronized SimulationRunParameters getSimulationRunParameters()
    {
        return(mSimulationRunParameters);
    }

    private synchronized void setSimulationRunParameters(SimulationRunParameters pSimulationRunParameters)
    {
        mSimulationRunParameters = pSimulationRunParameters;
    }

    private JButton createCancelButton()
    {
        JButton cancelButton = new JButton("cancel");
        cancelButton.addActionListener( new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                handleCancelButton();
            }
        } );
        return(cancelButton);
    }

    private void showCancelledSimulationDialog()
    {
        JOptionPane.showMessageDialog(getLauncherFrame(),
                                      "Your simulation has been cancelled",
                                      "simulation cancelled",
                                      JOptionPane.INFORMATION_MESSAGE);
    }

    private boolean mSimulationInProgress;


    boolean getSimulationInProgress()
    {
        return(null != getSimulationRunParameters());
    }

    private SimulationProgressReporter getSimulationProgressReporter()
    {
        return(mSimulationProgressReporter);
    }

    private SimulationController getSimulationController()
    {
        return(mSimulationController);
    }

    private synchronized void updateSimulationControlButtons(boolean pAllowsInterrupt)
    {
        SimulationController simulationController = getSimulationController();

        if(! getSimulationInProgress() || simulationController.getCancelled())
        {
            mStartButton.setEnabled(true);
            mPauseButton.setEnabled(false);
            mCancelButton.setEnabled(false);
            mResumeButton.setEnabled(false);
            mSimulatorsList.setEnabled(true);
        }
        else
        {
            mSimulatorsList.setEnabled(false);
            if(simulationController.getStopped())
            {
                mStartButton.setEnabled(false);
                mPauseButton.setEnabled(false);
                mCancelButton.setEnabled(pAllowsInterrupt);
                mResumeButton.setEnabled(pAllowsInterrupt);
            }
            else
            {
                mStartButton.setEnabled(false);
                mPauseButton.setEnabled(pAllowsInterrupt);
                mCancelButton.setEnabled(pAllowsInterrupt);
                mResumeButton.setEnabled(false);
            }
        }
    }


    private void handleResumeButton()
    {
        if(getSimulationInProgress())
        {
            SimulationController simulationController = getSimulationController();
            if(simulationController.getStopped())
            {
                simulationController.setStopped(false);
                updateSimulationControlButtons(true);
            }
        }
    }

    private void handlePauseButton()
    {
        if(getSimulationInProgress())
        {
            SimulationController simulationController = getSimulationController();
            if(! simulationController.getStopped())
            {
                simulationController.setStopped(true);
                updateSimulationControlButtons(true);
                mSimulationProgressReportHandler.clearLastUpdateTime();
            }
        }
    }

    private void handleCancelButton()
    {
        if(getSimulationInProgress())
        {
            SimulationController simulationController = getSimulationController();
            // first thing, set the cancelled flag on the simulation controller, to
            // notify the simulator that the simulation is cancelled
            simulationController.setCancelled(true);

            updateSimulationControlButtons(false);

            // finally, display the confirmation dialog box
            showCancelledSimulationDialog();
        }
    }

    private boolean processSimulationResults(OutputDescriptor pOutputDescriptor,
                                             String []pRequestedSymbolNames,
                                             SimulationResults pSimulationResults)
    {
        boolean success = false;
        try
        {
            OutputType outputType = pOutputDescriptor.mOutputType;

            SimulatorParameters simulatorParameters = pSimulationResults.getSimulatorParameters();
            // set the significant digits calculator
            
            Double relTol = simulatorParameters.getMaxAllowedRelativeError();
            if(null == relTol)
            {
                relTol = DEFAULT_ERROR_TOLERANCE_RELATIVE;
            }
            mSignificantDigitsCalculator.setRelTol(relTol);
            mSignificantDigitsCalculator.setAbsTol(simulatorParameters.getMaxAllowedAbsoluteError());

            String resultsLabel = pSimulationResults.createLabel();

            if(outputType.equals(OutputType.TABLE))
            {
                SimulationResultsTable simulationResultsTable = new SimulationResultsTable(pSimulationResults, 
                                                                                           mAppName,
                                                                                           resultsLabel,
                                                                                           mScientificNumberFormat);
                mFramePlacer.placeInCascadeFormat(simulationResultsTable);
                simulationResultsTable.setVisible(true);
            }
            else if(outputType.equals(OutputType.PLOT))
            {
                SimulationResultsPlot simResultsPlot = new SimulationResultsPlot(pSimulationResults, 
                                                                                 mAppName, 
                                                                                 resultsLabel);            
                mFramePlacer.placeInCascadeFormat(simResultsPlot);
                simResultsPlot.setVisible(true);
            }
            else
            {
                TimeSeriesOutputFormat outputFileFormat = pOutputDescriptor.mOutputFileFormat;
                boolean outputFileAppend = pOutputDescriptor.mOutputFileAppend;
                String outputFileName = pOutputDescriptor.mOutputFileName;
                File file = new File(outputFileName);
                FileWriter fileWriter = new FileWriter(file, outputFileAppend);
                PrintWriter printWriter = new PrintWriter(fileWriter);

                double []timeValues = pSimulationResults.getResultsTimeValues();
                Object []symbolValues = pSimulationResults.getResultsSymbolValues();

                DecimalFormatSymbols decimalFormatSymbols = mScientificNumberFormat.getDecimalFormatSymbols();
                outputFileFormat.updateDecimalFormatSymbols(decimalFormatSymbols);
                mScientificNumberFormat.setDecimalFormatSymbols(decimalFormatSymbols);
                TimeSeriesSymbolValuesReporter.reportTimeSeriesSymbolValues(printWriter,
                                                                            pRequestedSymbolNames,
                                                                            timeValues,
                                                                            symbolValues,
                                                                            mScientificNumberFormat,
                                                                            outputFileFormat);
                printWriter.flush();
                JOptionPane.showMessageDialog(getLauncherFrame(),
                                              "output saved to file:\n" + outputFileName,
                                              "output saved",
                                              JOptionPane.INFORMATION_MESSAGE);
                mScientificNumberFormat.setDecimalFormatSymbols(new DecimalFormatSymbols());
            }

            success = true;
        }
        catch(Exception e)
        {
            ExceptionNotificationOptionPane optionPane = new ExceptionNotificationOptionPane(e, "Sorry, there was an error processing the simulation results.  The specific error message is:");
            optionPane.createDialog(getLauncherFrame(),
                                    "Failure processing simulation results").show();
        }

        return(success);
    }

    private void runSimulation(SimulationRunParameters pSimulationRunParameters)
    {
        ISimulator simulator = pSimulationRunParameters.mSimulator;
        SimulationResults simulationResults = null;
        long startTime = 0;

        try
        {

            // initializing the simulator might take a while,
            updateSimulationControlButtons(false);
            if(! simulator.isInitialized())
            {
                simulator.initialize(mModel);
                simulator.setController(mSimulationController);
                simulator.setProgressReporter(mSimulationProgressReporter);
            }
            updateSimulationControlButtons(simulator.allowsInterrupt());

            startTime = System.currentTimeMillis(); 

            simulationResults = simulator.simulate(pSimulationRunParameters.mStartTime,
                                                   pSimulationRunParameters.mEndTime,
                                                   pSimulationRunParameters.mSimulatorParameters,
                                                   pSimulationRunParameters.mNumTimePoints,
                                                   pSimulationRunParameters.mRequestedSymbolNames);

        }

        catch(Throwable e)
        {
            simulationEndCleanup(simulator);
            ExceptionNotificationOptionPane optionPane = new ExceptionNotificationOptionPane(e, "Sorry, the simulation failed.  The specific error message is:");
            optionPane.createDialog(getLauncherFrame(),
                                    "Failure running simulation").show();
            return;
        }


        // handle the simulation results

            if(! mSimulationController.getCancelled() && null != simulationResults)
            {
                if(mHandleOutputInternally)
                {
                    mResultsList.add(simulationResults);
                    updateSimulationResultsList();

                    long deltaTime = System.currentTimeMillis() - startTime;
                    System.out.println("simulation time: " + ((double) deltaTime)/MILLISECONDS_PER_SECOND + " seconds");

                    boolean success = processSimulationResults(pSimulationRunParameters.mOutputDescriptor,
                                                               pSimulationRunParameters.mRequestedSymbolNames, 
                                                               simulationResults);
                }
                else
                {
                    mResultsQueue.add(simulationResults);
                }
            }

        simulationEndCleanup(simulator);
    }

    private void simulationEndCleanup(ISimulator pSimulator)
    {
        setSimulationRunParameters(null);

        Iterator listenerIter = mListeners.iterator();
        while(listenerIter.hasNext())
        {
            Listener listener = (Listener) listenerIter.next();
            listener.simulationEnding();
        }

        updateSimulationControlButtons(pSimulator.allowsInterrupt());

        mSimulationProgressReporter.setSimulationFinished(true);
        mSimulationProgressReportHandler.clearLastUpdateTime();
    }

    private void handleStartButton() 
    {
        if(! getSimulationInProgress())
        {
            SimulationController simulationController = getSimulationController();
            simulationController.setCancelled(false);
            simulationController.setStopped(false);

            SimulationProgressReporter simulationProgressReporter = getSimulationProgressReporter();
            simulationProgressReporter.setSimulationFinished(false);
            SimulationRunParameters simulationRunParameters = createSimulationRunParameters();
            if(null != simulationRunParameters)
            {
                setSimulationRunParameters(simulationRunParameters);

                Iterator listenerIter = mListeners.iterator();
                while(listenerIter.hasNext())
                {
                    Listener listener = (Listener) listenerIter.next();
                    listener.simulationStarting();
                }

                synchronized(mSimulationRunner)
                {
                    mSimulationRunner.notifyAll();
                }
            }
        }
    }

    private JButton createResumeButton()
    {
        JButton resumeButton = new JButton("resume");
        resumeButton.addActionListener( new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                handleResumeButton();
            }
        } );
        return(resumeButton);
    }

    private JButton createStartButton() 
    {
        JButton startButton = new JButton("start");
        startButton.addActionListener( new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                handleStartButton();
            }
        } );
        return(startButton);
    }

    private JButton createPauseButton()
    {
        JButton pauseButton = new JButton("pause");
        pauseButton.addActionListener( new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                handlePauseButton();
            }
        } );
        return(pauseButton);
    }    

    private void updateSimulatorParametersPanel()
    {
        updateSimulatorParametersPanel(mSimulatorsList.getSelectedIndex());
    }

    private ISimulator getSimulatorInstance(String pSimulatorAlias)
    {
        ISimulator simulator = null;
        try
        {
            simulator = (ISimulator) getSimulatorRegistry().getInstance(pSimulatorAlias);
        }
        catch(Exception e)
        {
            ExceptionNotificationOptionPane optionPane = new ExceptionNotificationOptionPane(e, "Sorry, there was an error creating an instance of the simulator \"" + pSimulatorAlias + "\".  The specific error message is:");
            optionPane.createDialog(getLauncherFrame(),
                                    "Failed to instantiate simulator").show();
        }
        
        return simulator;
    }
    
    private void updateSimulatorParametersPanel(int pSimulatorIndex)
    {
        String simulatorAlias = (String) mSimulatorsList.getModel().getElementAt(pSimulatorIndex);
        if(null == simulatorAlias)
        {
            throw new IllegalStateException("no simulator was selected");
        }
        else
        {
            ISimulator simulator = getSimulatorInstance(simulatorAlias);
            if(null == simulator)
            {
                return;
            }
            SimulatorParameters simParams = simulator.getDefaultSimulatorParameters();
            Integer ensembleSize = simParams.getEnsembleSize();
            if(null != ensembleSize)
            {
                mEnsembleField.setText(ensembleSize.toString());
                mEnsembleField.setEnabled(true);
                mEnsembleFieldLabel.setEnabled(true);
            }
            else
            {
                mEnsembleField.setText("");
                mEnsembleField.setEnabled(false);
                mEnsembleFieldLabel.setEnabled(false);
            }
            
            Double stepSizeFraction = simParams.getStepSizeFraction();
            if(null != stepSizeFraction)
            {
                mStepSizeFractionField.setText(stepSizeFraction.toString());
                mStepSizeFractionField.setEnabled(true);
                mStepSizeFractionFieldLabel.setEnabled(true);
            }
            else
            {
                mStepSizeFractionField.setText("");
                mStepSizeFractionField.setEnabled(false);
                mStepSizeFractionFieldLabel.setEnabled(false);
            }
            
            
            
            Double maxAllowedRelativeError = simParams.getMaxAllowedRelativeError();
            if(null != maxAllowedRelativeError)
            {
                mAllowedRelativeErrorField.setText(maxAllowedRelativeError.toString());
                mAllowedRelativeErrorField.setEnabled(true);
                mAllowedRelativeErrorFieldLabel.setEnabled(true);
            }
            else
            {
                mAllowedRelativeErrorField.setText("");
                mAllowedRelativeErrorField.setEnabled(false);
                mAllowedRelativeErrorFieldLabel.setEnabled(false);
            }
            
            Double maxAllowedAbsoluteError = simParams.getMaxAllowedAbsoluteError();
            if(null != maxAllowedAbsoluteError)
            {
                mAllowedAbsoluteErrorField.setText(maxAllowedAbsoluteError.toString());
                mAllowedAbsoluteErrorField.setEnabled(true);
                mAllowedAbsoluteErrorFieldLabel.setEnabled(true);
            }
            else
            {
                mAllowedAbsoluteErrorField.setText("");
                mAllowedAbsoluteErrorField.setEnabled(false);
                mAllowedAbsoluteErrorFieldLabel.setEnabled(false);
            }
            
            Integer numHistoryBins = simParams.getNumHistoryBins();
            if(null != numHistoryBins)
            {
                mNumHistoryBinsField.setText(numHistoryBins.toString());
                if(null != mModel && (mModel.containsDelayedOrMultistepReaction()))
                {
                    mNumHistoryBinsField.setEnabled(true);
                    mNumHistoryBinsFieldLabel.setEnabled(true);
                }
                else
                {
                    mNumHistoryBinsField.setEnabled(false);
                    mNumHistoryBinsFieldLabel.setEnabled(false);
                }
            }
            else
            {
                mNumHistoryBinsField.setText("");
                mNumHistoryBinsField.setEnabled(false);
                mNumHistoryBinsFieldLabel.setEnabled(false);
            }
            
            boolean allowsInterrupt = simulator.allowsInterrupt();
            updateSimulationControlButtons(allowsInterrupt);
        }
    }

    private JList createSimulatorsList()
    {
        Set simulatorAliases = getSimulatorAliasesCopy();
        assert (simulatorAliases.size() > 0) : "no simulators found";
        java.util.List simulatorAliasesList = new LinkedList(simulatorAliases);
        Collections.sort(simulatorAliasesList);
        Object []simulatorAliasObjects = simulatorAliasesList.toArray();
        if(simulatorAliasObjects.length == 0)
        {
            throw new IllegalStateException("there are no simulators available");
        }
        final JList simulatorsList = new JList();
        mSimulatorsList = simulatorsList;
        simulatorsList.setVisibleRowCount(SIMULATORS_LIST_BOX_ROW_COUNT);
        simulatorsList.setListData(simulatorAliasObjects);
        simulatorsList.setSelectedIndex(0);
        simulatorsList.setFixedCellWidth(185);
        // we will call updateSimulatorParametersPanel() later in the
        // initialization process, after we have called setModel();
        simulatorsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ListSelectionListener listSelectionListener = new ListSelectionListener()
        {
            public void valueChanged(ListSelectionEvent e)
            {
                if(e.getValueIsAdjusting())
                {
                    updateSimulatorParametersPanel();
                }
            }
        };
        simulatorsList.addListSelectionListener(listSelectionListener);
        return(simulatorsList);
    }

    private JPanel createSimulatorsListPanel()
    {
        JPanel panel = new JPanel();
        JLabel label = new JLabel("simulators:");
        Box box = new Box(BoxLayout.Y_AXIS);
        box.add(label);
        JList list = createSimulatorsList();
        JScrollPane scrollPane = new JScrollPane(list);
        box.add(scrollPane);
        panel.add(box);
        panel.setBorder(BorderFactory.createEtchedBorder());
        return(panel);
    }

    private JPanel createButtonPanel()
    {
        JPanel panel = new JPanel();
        Box box = new Box(BoxLayout.Y_AXIS);

        JLabel label = new JLabel("controller:");

        JPanel labelPanel = new JPanel();
        Box labelBox = new Box(BoxLayout.X_AXIS);
        labelBox.add(label);
        JPanel padding5 = new JPanel();
        padding5.setBorder(BorderFactory.createEmptyBorder(2, 1, 2, 1));
        labelBox.add(padding5);
        labelPanel.add(labelBox);

        box.add(labelPanel);

        JPanel padding2 = new JPanel();
        padding2.setBorder(BorderFactory.createEmptyBorder(2, 1, 2, 1));
        box.add(padding2);
        JButton startButton = createStartButton();
        padding2.add(startButton);
        mStartButton = startButton;

        JPanel padding1 = new JPanel();
        padding1.setBorder(BorderFactory.createEmptyBorder(2, 1, 2, 1));
        JButton cancelButton = createCancelButton();
        padding1.add(cancelButton);
        box.add(padding1);
        mCancelButton = cancelButton;

        JPanel padding3 = new JPanel();
        padding3.setBorder(BorderFactory.createEmptyBorder(2, 1, 2, 1));
        box.add(padding3);
        JButton pauseButton = createPauseButton();
        padding3.add(pauseButton);
        mPauseButton = pauseButton;

        JPanel padding4 = new JPanel();
        padding4.setBorder(BorderFactory.createEmptyBorder(2, 1, 2, 1));
        box.add(padding4);
        JButton resumeButton = createResumeButton();
        padding4.add(resumeButton);
        mResumeButton = resumeButton;

        panel.add(box);
        panel.setBorder(BorderFactory.createEtchedBorder());
        return(panel);
    }

    private JList createSymbolList()
    {
        JList symbolListBox = new JList();
        mSymbolList = symbolListBox;
        symbolListBox.setVisibleRowCount(SYMBOL_LIST_BOX_ROW_COUNT);
        symbolListBox.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        symbolListBox.setFixedCellWidth(200);
        return(symbolListBox);
    }

    private void populateSymbolListPanel()
    {
        Object []symbolArray = mModel.getOrderedResultsSymbolNamesArray();
        mSymbolList.setListData(symbolArray);
    }

    private void handleSelectAllSymbolsButton()
    {
        int numSymbols = mSymbolList.getModel().getSize();
        int []selectedSymbols = new int[numSymbols];
        for(int ctr = 0; ctr < numSymbols; ++ctr)
        {
            selectedSymbols[ctr] = ctr;
        }
        mSymbolList.setSelectedIndices(selectedSymbols);
    }

    private JPanel createSymbolListPanel()
    {
        JPanel panel = new JPanel();
        JLabel label = new JLabel("view symbols:");
        Box box = new Box(BoxLayout.Y_AXIS);
        box.add(label);
        JList list = createSymbolList();
        JScrollPane scrollPane = new JScrollPane(list);
        box.add(scrollPane);
        JButton selectAllButton = new JButton("select all");
        selectAllButton.setMinimumSize(new Dimension(100, 50)); // this makes sure all the text is displayed
        box.add(selectAllButton);
        selectAllButton.addActionListener(
            new ActionListener()
            {
                public void actionPerformed(ActionEvent e)
                {
                    handleSelectAllSymbolsButton();
                }
            }
            );
        panel.add(box);

        panel.setBorder(BorderFactory.createEtchedBorder());
        return(panel);
    }

    private static final int NUM_COLUMNS_TIME_FIELD = 10;

    private JPanel createStartStopTimePanel()
    {
        JPanel panel = new JPanel();
        Box box = new Box(BoxLayout.Y_AXIS);

        JPanel startStopPanel = new JPanel();


        JPanel startPanel = new JPanel();
        JLabel startLabel = new JLabel("start:");
        startPanel.add(startLabel);
        JTextField startField = new JTextField("0.0", NUM_COLUMNS_TIME_FIELD);
        mStartTimeField = startField;
        startPanel.add(startField);
        startStopPanel.add(startPanel);

        JPanel stopPanel = new JPanel();
        JLabel stopLabel = new JLabel("stop:");
        stopPanel.add(stopLabel);
        JTextField stopField = new JTextField(NUM_COLUMNS_TIME_FIELD);
        mStopTimeField = stopField;
        stopPanel.add(stopField);
        startStopPanel.add(stopPanel);

        box.add(startStopPanel);

        JPanel numPointsPanel = new JPanel();
        JLabel numPointsLabel = new JLabel("number of results points:");
        numPointsPanel.add(numPointsLabel);
        JTextField numPointsField = new JTextField("100", NUM_COLUMNS_TIME_FIELD);
        mNumPointsField = numPointsField;
        numPointsPanel.add(numPointsField);
        box.add(numPointsPanel);

        JPanel ensemblePanel = new JPanel();
        JPanel ensembleLabelPanel = new JPanel();
        Box ensembleLabelBox = new Box(BoxLayout.Y_AXIS);
        JLabel ensembleLabel = new JLabel("stochastic ensemble size:");
        ensembleLabelBox.add(ensembleLabel);
        ensembleLabelPanel.add(ensembleLabelBox);
        ensemblePanel.add(ensembleLabelPanel);
        JTextField ensembleField = new JTextField(NUM_COLUMNS_TIME_FIELD);
        mEnsembleField = ensembleField;
        mEnsembleFieldLabel = ensembleLabel;
        ensemblePanel.add(ensembleField);
        box.add(ensemblePanel);

        JPanel stepSizeFractionPanel = new JPanel();
        JPanel stepSizeFractionLabelPanel = new JPanel();
        Box stepSizeFractionLabelBox = new Box(BoxLayout.Y_AXIS);
        JLabel stepSizeFractionLabel = new JLabel("step size (fractional):");
        stepSizeFractionLabelBox.add(stepSizeFractionLabel);
        stepSizeFractionLabelPanel.add(stepSizeFractionLabelBox);
        stepSizeFractionPanel.add(stepSizeFractionLabelPanel);
        JTextField stepSizeFractionField = new JTextField(NUM_COLUMNS_TIME_FIELD);
        mStepSizeFractionField = stepSizeFractionField;
        mStepSizeFractionFieldLabel = stepSizeFractionLabel;
        stepSizeFractionPanel.add(stepSizeFractionField);
        box.add(stepSizeFractionPanel);

        JPanel allowedRelativeErrorPanel = new JPanel();
        JPanel allowedRelativeErrorLabelPanel = new JPanel();
        Box allowedRelativeErrorLabelBox = new Box(BoxLayout.Y_AXIS);
        JLabel allowedRelativeErrorLabel = new JLabel("max allowed relative error:");
        allowedRelativeErrorLabelBox.add(allowedRelativeErrorLabel);
        allowedRelativeErrorLabelPanel.add(allowedRelativeErrorLabelBox);
        allowedRelativeErrorPanel.add(allowedRelativeErrorLabelPanel);
        JTextField allowedRelativeErrorField = new JTextField(NUM_COLUMNS_TIME_FIELD);
        mAllowedRelativeErrorField = allowedRelativeErrorField;
        mAllowedRelativeErrorFieldLabel = allowedRelativeErrorLabel;
        allowedRelativeErrorPanel.add(allowedRelativeErrorField);
        box.add(allowedRelativeErrorPanel);

        JPanel allowedAbsoluteErrorPanel = new JPanel();
        JPanel allowedAbsoluteErrorLabelPanel = new JPanel();
        Box allowedAbsoluteErrorLabelBox = new Box(BoxLayout.Y_AXIS);
        JLabel allowedAbsoluteErrorLabel = new JLabel("max allowed absolute error:");
        allowedAbsoluteErrorLabelBox.add(allowedAbsoluteErrorLabel);
        allowedAbsoluteErrorLabelPanel.add(allowedAbsoluteErrorLabelBox);
        allowedAbsoluteErrorPanel.add(allowedAbsoluteErrorLabelPanel);
        JTextField allowedAbsoluteErrorField = new JTextField(NUM_COLUMNS_TIME_FIELD);
        mAllowedAbsoluteErrorField = allowedAbsoluteErrorField;
        mAllowedAbsoluteErrorFieldLabel = allowedAbsoluteErrorLabel;
        allowedAbsoluteErrorPanel.add(allowedAbsoluteErrorField);
        box.add(allowedAbsoluteErrorPanel);

        // create panel for the "number of history bins"
        JPanel numHistoryBinsPanel = new JPanel();
        JPanel numHistoryBinsLabelPanel = new JPanel();
        Box numHistoryBinsLabelBox = new Box(BoxLayout.Y_AXIS);
        JLabel numHistoryBinsLabel = new JLabel("number of history bins:");
        numHistoryBinsLabelBox.add(numHistoryBinsLabel);
        numHistoryBinsLabelPanel.add(numHistoryBinsLabelBox);
        numHistoryBinsPanel.add(numHistoryBinsLabelPanel);
        JTextField numHistoryBinsField = new JTextField(NUM_COLUMNS_TIME_FIELD);
        mNumHistoryBinsField = numHistoryBinsField;
        mNumHistoryBinsFieldLabel = numHistoryBinsLabel;
        numHistoryBinsPanel.add(numHistoryBinsField);
        box.add(numHistoryBinsPanel);

        panel.add(box);
        panel.setBorder(BorderFactory.createEtchedBorder());

        return(panel);
    }

    private static final int NUM_COLUMNS_FILE_NAME = 12;

    private void handleBadInput(String pTitle, String pMessage)
    {
        JOptionPane.showMessageDialog(getLauncherFrame(),
                                      pTitle,
                                      pMessage,
                                      JOptionPane.INFORMATION_MESSAGE);
    }

    private String []createSelectedSymbolNames()
    {
        Object []symbolsSelected = mSymbolList.getSelectedValues();
        if(symbolsSelected.length == 0)
        {
            handleBadInput("no symbol selected", "Please select at least one symbol to observe");
            return(null);
        }
        int numSymbol = symbolsSelected.length;

        String []symbolsSelectedNames = new String[numSymbol];
        for(int ctr = 0; ctr < numSymbol; ctr++)
        {
            String symbolName = (String) symbolsSelected[ctr];
            symbolsSelectedNames[ctr] = symbolName;
        }
        return(symbolsSelectedNames);
    }

    private OutputDescriptor createOutputDescriptor()
    {
        OutputDescriptor outputDescriptor = new OutputDescriptor();
        
        String outputTypeStr = mOutputType.toString();
        OutputType outputType = OutputType.get(outputTypeStr);
        assert (null != outputType) : "null output type";
            
        if(outputType.equals(OutputType.PLOT))
        {
            if(mSymbolList.getSelectedValues().length > SimulationResultsPlot.MAX_NUM_SYMBOLS_TO_PLOT)
            {
                handleBadInput("too many symbols to plot", "maximum number of symbols that can be plotted simultaneously is: " + SimulationResultsPlot.MAX_NUM_SYMBOLS_TO_PLOT);
                return(null);
            }
        }
            
        outputDescriptor.mOutputType = outputType;
        if(mOutputType.equals(OutputType.FILE))
        {
            File outputFile = mOutputFile;
            if(null == outputFile)
            {
                handleBadInput("output file name was not specified", "Saving the results to a file requires specifying a file name");
                return(null);
            }
            String fileName = outputFile.getAbsolutePath();
            assert (null != fileName && fileName.trim().length() > 0) : "invalid output file name";
            outputDescriptor.mOutputFileName = fileName;
                
            TimeSeriesOutputFormat outputFileFormat = getSelectedOutputFileFormat();
            if(null == outputFileFormat)
            {
                throw new IllegalStateException("null output file format");
            }
            outputDescriptor.mOutputFileFormat = outputFileFormat;
            boolean append = mOutputFileAppendCheckBox.isSelected();
            outputDescriptor.mOutputFileAppend = append;
        }
        else
        {
            outputDescriptor.mOutputFileName = null;
            outputDescriptor.mOutputFileAppend = false;
            outputDescriptor.mOutputFileFormat = TimeSeriesOutputFormat.CSV_GNUPLOT;
        }

        return(outputDescriptor);
    }

    private SimulationRunParameters createSimulationRunParameters()
    {
        SimulationRunParameters srp = new SimulationRunParameters();
        
        String startTimeStr = mStartTimeField.getText();
        Double startTime = null;
        SimulationRunParameters retVal = null;
        if(null != startTimeStr)
        {
            try
            {
                startTime = new Double(startTimeStr);
            }
            catch(NumberFormatException e)
            {
                // do nothing
            }
        }
        if(null == startTime)
        {
            handleBadInput("invalid start time", "The start time that you specified is invalid.\nPlease enter a numeric start time.");
            return(retVal);
        }
        double startTimeVal = startTime.doubleValue();

        srp.mStartTime = startTimeVal;

        String stopTimeStr = mStopTimeField.getText();
        Double stopTime = null;
        if(null != stopTimeStr)
        {
            try
            {
                stopTime = new Double(stopTimeStr);
            }
            catch(NumberFormatException e)
            {
                // do nothing
            }
        }
        if(null == stopTime)
        {
            handleBadInput("invalid stop time", "The stop time that you specified is invalid.\nPlease enter a numeric stop time.");
            return(retVal);
        }
        else if(stopTime.doubleValue() <= startTimeVal)
        {
            handleBadInput("invalid stop time", "The stop time that you specified is invalid.\nIt must be greater than the start time.");
            return(retVal);
        }
        double stopTimeVal = stopTime.doubleValue();

        srp.mEndTime = stopTimeVal;

        String numPointsStr = mNumPointsField.getText();
        Integer numPoints = null;
        if(null != numPointsStr)
        {
            try
            {
                numPoints = new Integer(numPointsStr);
            }
            catch(NumberFormatException e)
            {
                // do nothing
            }
        }
        if(null == numPoints)
        {
            handleBadInput("invalid number of samples", "The number of samples specified is invalid.\nPlease enter an integer number of samples.");
            return(retVal);
        }
        int numTimePoints = numPoints.intValue();
        int minNumTimePoints = ISimulator.MIN_NUM_RESULTS_TIME_POINTS;
        if(numTimePoints < minNumTimePoints)
        {
            handleBadInput("invalid number of samples", "The number of samples specified must be greater than or equal to " + Integer.toString(minNumTimePoints));
            return(retVal);
        }

        srp.mNumTimePoints = numTimePoints;
 
        int simulatorIndex = mSimulatorsList.getSelectedIndex();
        String simulatorAlias = null;
        if(simulatorIndex != -1)
        {
            simulatorAlias = (String) mSimulatorsList.getModel().getElementAt(simulatorIndex);
        }
        if(simulatorAlias == null)
        {
            handleBadInput("no simulator was selected", "Please select a simulator to use");
            return(retVal);
        }

        ISimulator simulator = getSimulatorInstance(simulatorAlias);
        if(null == simulator)
        {
            return retVal;
        }

        srp.mSimulator = simulator;
        srp.mSimulatorAlias = simulatorAlias;

        String ensembleStr = mEnsembleField.getText();
        
        SimulatorParameters simulatorParameters = simulator.getDefaultSimulatorParameters();
        srp.mSimulatorParameters = simulatorParameters;

        Integer ensembleSize = null;
        if(null != ensembleStr && ensembleStr.trim().length() > 0)
        {
            try
            {
                ensembleSize = new Integer(ensembleStr);
            }
            catch(NumberFormatException e)
            {
                // do nothing
            }
            if(null == ensembleSize || ensembleSize.intValue() <= 0)
            {
                handleBadInput("invalid ensemble size", "The ensemble size you specified is invalid");
                return(retVal);
            }
        }
        if(null != ensembleSize)
        {
            simulatorParameters.setEnsembleSize(ensembleSize);
        }

        String stepSizeFractionStr = mStepSizeFractionField.getText();

        Double stepSizeFraction = null;
        if(null != stepSizeFractionStr && stepSizeFractionStr.trim().length() > 0)
        {
            try
            {
                stepSizeFraction = new Double(stepSizeFractionStr);
            }
            catch(NumberFormatException e)
            {
                handleBadInput("invalid step size fraction", "The fractional step size you specified is invalid");
                return(retVal);
            }
        }
        if(null != stepSizeFraction)
        {
            simulatorParameters.setStepSizeFraction(stepSizeFraction);
        }

        String allowedRelativeErrorStr = mAllowedRelativeErrorField.getText();
        Double allowedRelativeError = null;
        if(null != allowedRelativeErrorStr && allowedRelativeErrorStr.trim().length() > 0)
        {
            try
            {
                allowedRelativeError = new Double(allowedRelativeErrorStr);
            }
            catch(NumberFormatException e)
            {
                handleBadInput("invalid allowed fractional error", "The allowed fractional error you specified is invalid");
                return(retVal);
            }
        }

        simulatorParameters.setMaxAllowedRelativeError(allowedRelativeError);

        String allowedAbsoluteErrorStr = mAllowedAbsoluteErrorField.getText();
        Double allowedAbsoluteError = null;
        if(null != allowedAbsoluteErrorStr && allowedAbsoluteErrorStr.trim().length() > 0)
        {
            try
            {
                allowedAbsoluteError = new Double(allowedAbsoluteErrorStr);
            }
            catch(NumberFormatException e)
            {
                handleBadInput("invalid allowed fractional error", "The allowed fractional error you specified is invalid");
                return(retVal);
            }
        }
        simulatorParameters.setMaxAllowedAbsoluteError(allowedAbsoluteError);

        String numHistoryBinsStr = mNumHistoryBinsField.getText();
        Integer numHistoryBins = null;
        if(null != numHistoryBinsStr && numHistoryBinsStr.trim().length() > 0)
        {
            try
            {
                numHistoryBins = new Integer(numHistoryBinsStr);
            }
            catch(NumberFormatException e)
            {
                handleBadInput("invalid number of history bins", "The number of history bins you specified is invalid");
                return(retVal);
            }
        }
        if(null != numHistoryBins)
        {
            simulatorParameters.setNumHistoryBins(numHistoryBins.intValue());
        }

        String []selectedSymbolNames = createSelectedSymbolNames();
        if(null == selectedSymbolNames)
        {
            return(retVal);
        }

        srp.mRequestedSymbolNames = createSelectedSymbolNames();

        if(mHandleOutputInternally)
        {
            OutputDescriptor outputDescriptor = createOutputDescriptor();
            if(null == outputDescriptor)
            {
                return(retVal);
            }
            srp.mOutputDescriptor = outputDescriptor;
        }
        else
        {
            srp.mOutputDescriptor = null;
        }

        retVal = srp;
        return(retVal);
    }

    private void enableOutputFieldSection(boolean pEnabled)
    {
        mOutputFileField.setEnabled(pEnabled);
        mOutputFileAppendCheckBox.setEnabled(pEnabled);
        mOutputFileAppendLabel.setEnabled(pEnabled);
        mOutputFileFormatListLabel.setEnabled(pEnabled);
        mOutputFileFormatList.setEnabled(pEnabled);

        if(pEnabled && mOutputFileField.getText().length() == 0)
        {
            mOutputFileField.setText("[output file; click to edit]");
        }
    }

    private void enablePlotFieldSelection(boolean pEnabled)
    {
        // nothing to do
    }

    private void handleOutputTypeSelection(ActionEvent e)
    {
        String outputTypeStr = e.getActionCommand();
        OutputType outputType = OutputType.get(outputTypeStr);
        mOutputType = outputType;
        
        if(null != outputType)
        {
            if(outputType.equals(OutputType.TABLE))
            {
                enableOutputFieldSection(false);
                enablePlotFieldSelection(false);
            }
            else if(outputType.equals(OutputType.PLOT))
            {
                enableOutputFieldSection(false);
                enablePlotFieldSelection(true);
            }
            else if(outputType.equals(OutputType.FILE))
            {
                enableOutputFieldSection(true);
                enablePlotFieldSelection(false);
            }
            else
            {
                assert false: "unknown output type";
            }
        }
        else
        {
            throw new IllegalStateException("unknown output type: " + outputType);
        }
    }

    public void setCurrentDirectory(File pCurrentDirectory)
    {
        mCurrentDirectory = pCurrentDirectory;
    }

    private File getCurrentDirectory()
    {
        return(mCurrentDirectory);
    }

    private void handleOutputFileMouseClick()
    {
        OutputType outputType = mOutputType;
        if(outputType.equals(OutputType.FILE))
        {
            FileChooser outputFileChooser = new FileChooser();
            outputFileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            outputFileChooser.setDialogTitle("Please specify the output file; the extension \".csv\" is recommended");
            File currentDirectory = getCurrentDirectory();
            if(null != mOutputFile)
            {
                outputFileChooser.setSelectedFile(mOutputFile);
            }
            else if(null != currentDirectory)
            {
                outputFileChooser.setCurrentDirectory(currentDirectory);
            }
            outputFileChooser.showDialog(mLauncherFrame, "approve");
            File outputFile = outputFileChooser.getSelectedFile();
            if(null != outputFile)
            {
                String outputFileName = outputFile.getAbsolutePath(); 
                boolean doUpdate = true;
                if(outputFile.exists() && 
                   (null == mOutputFile ||
                    !mOutputFile.equals(outputFile)) && 
                   ! mOutputFileAppendCheckBox.isSelected())
                {
                    doUpdate = FileChooser.handleOutputFileAlreadyExists(mLauncherFrame, outputFileName);
                }
                if(doUpdate)
                {
                    mOutputFile = outputFile;
                    mOutputFileField.setText(outputFileName);
                }
            }   

        }
    }

    private void updateSimulationResultsList()
    {
        SimulationResults []simulationResultsArray = (SimulationResults []) mResultsList.toArray(new SimulationResults[0]);
        int numSimulationResults = simulationResultsArray.length;
        String []simResultsLabels = new String[numSimulationResults];
        String modelName = null;
        if(null != mModel)
        {
            modelName = mModel.getName();
        }
        for(int i = 0; i < numSimulationResults; ++i)
        {
            SimulationResults simulationResults = simulationResultsArray[i];
            String simulationResultsLabel = simulationResults.createLabel();
            simResultsLabels[i] = simulationResultsLabel;
        }
        mSimulationResultsList.setListData(simResultsLabels);
        int selectedIndex = 0;
        if(numSimulationResults > 0)
        {
            mSimulationResultsList.setSelectedIndex(numSimulationResults - 1);
            JScrollBar vertScrollBar = mSimulationResultsListScrollPane.getVerticalScrollBar();
            vertScrollBar.setValue(vertScrollBar.getMaximum());
        }
        
        boolean enableResultsListButton = (mResultsList.size() > 0);
        mProcessSimulationResultsButton.setEnabled(enableResultsListButton);
    }

    private void handleProcessSimulationResultsButton() 
    {
        int selectedIndex = mSimulationResultsList.getSelectedIndex();
        if(selectedIndex >= 0)
        {
            SimulationResults simulationResults = (SimulationResults) mResultsList.get(selectedIndex);
            OutputDescriptor outputDescriptor = createOutputDescriptor();
            String []selectedSymbols = simulationResults.getResultsSymbolNames();
            
            processSimulationResults(outputDescriptor,
                    selectedSymbols,
                    simulationResults);
            
        }
    }

    private JPanel createResultsPanel()
    {
        JPanel resultsPanel = new JPanel();
        resultsPanel.setAlignmentX(Container.CENTER_ALIGNMENT);
        resultsPanel.setBorder(BorderFactory.createEtchedBorder());
        BoxLayout resultsBox = new BoxLayout(resultsPanel, BoxLayout.PAGE_AXIS);
        resultsPanel.setLayout(resultsBox);
        JLabel resultsLabel = new JLabel("simulation results list:");
        resultsLabel.setAlignmentX(Container.LEFT_ALIGNMENT);
        resultsPanel.add(resultsLabel);
        JList resultsList = new JList();
        mSimulationResultsList = resultsList;
        resultsList.setVisibleRowCount(RESULTS_LIST_ROW_COUNT);
        resultsList.setSelectedIndex(0);
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.setFixedCellWidth(330);
        JScrollPane scrollPane = new JScrollPane(resultsList);
        mSimulationResultsListScrollPane = scrollPane;
        resultsPanel.add(scrollPane);
        JButton processResultsButton = new JButton("reprocess results");
        processResultsButton.setMinimumSize(new Dimension(170, 25));
        processResultsButton.setPreferredSize(new Dimension(170, 25));
        processResultsButton.setMaximumSize(new Dimension(170, 25));
        processResultsButton.setEnabled(false);
        processResultsButton.addActionListener(
            new ActionListener()
            {
                public void actionPerformed(ActionEvent e)
                {
                    handleProcessSimulationResultsButton();
                }
            });
        mProcessSimulationResultsButton = processResultsButton;
        resultsList.addMouseListener(
            new MouseAdapter()
            {
                public void mouseClicked(MouseEvent e)
                {
                    if(mProcessSimulationResultsButton.isEnabled() &&
                       e.getClickCount() == 2)
                    {
                        handleProcessSimulationResultsButton();
                    }
                }
            }
            );
        resultsPanel.add(processResultsButton);
        updateSimulationResultsList();
        return(resultsPanel);
    }

    private JPanel createOutputPanel()
    {
        ButtonGroup buttonGroup = new ButtonGroup();
        
        JPanel outputPanel = new JPanel();
        outputPanel.setBorder(BorderFactory.createEtchedBorder());
        outputPanel.setAlignmentX(Container.LEFT_ALIGNMENT);

        BoxLayout outputBox = new BoxLayout(outputPanel, BoxLayout.PAGE_AXIS);
        
        outputPanel.setLayout(outputBox);

        JLabel outputLabel = new JLabel("Output Type -- specify what do do with the simulation results:");
        outputLabel.setAlignmentX(Container.LEFT_ALIGNMENT);
        outputPanel.add(outputLabel);

        ActionListener buttonListener = new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                handleOutputTypeSelection(e);
            }
        };

        JPanel plotPanel = new JPanel();
        plotPanel.setAlignmentX(Container.LEFT_ALIGNMENT);
        plotPanel.setMaximumSize(new Dimension(61, 50));
        JRadioButton plotButton = new JRadioButton(OutputType.PLOT.toString(), true);
        plotButton.addActionListener(buttonListener);
        plotButton.setSelected(true);
        plotPanel.add(plotButton);
        buttonGroup.add(plotButton);
        outputPanel.add(plotPanel);

        JPanel tablePanel = new JPanel();
        tablePanel.setAlignmentX(Container.LEFT_ALIGNMENT);
        tablePanel.setMaximumSize(new Dimension(65, 50));
        JRadioButton tableButton = new JRadioButton(OutputType.TABLE.toString(), true);
        tableButton.addActionListener(buttonListener);
        tableButton.setSelected(false);
        buttonGroup.add(tableButton);
        tablePanel.add(tableButton);
        outputPanel.add(tablePanel);

        JPanel filePanel = new JPanel();
        filePanel.setAlignmentX(Container.LEFT_ALIGNMENT);
        JRadioButton fileButton = new JRadioButton(OutputType.FILE.toString(), false);
        fileButton.setToolTipText(TOOLTIP_FILE_OUTPUT);
        fileButton.addActionListener(buttonListener);
        buttonGroup.add(fileButton);
        filePanel.add(fileButton);
        JPanel fileNamePanel = new JPanel();
        Box fileBox = new Box(BoxLayout.Y_AXIS);
        fileBox.setToolTipText(TOOLTIP_FILE_OUTPUT);
        JTextField fileNameTextField = new JTextField();
        fileNameTextField.setColumns(OUTPUT_FILE_TEXT_FIELD_SIZE_CHARS);
        mOutputFileField = fileNameTextField;
        fileNameTextField.setEditable(false);
        fileNameTextField.addMouseListener(new MouseAdapter()
        {
            public void mouseClicked(MouseEvent e)
            {
                handleOutputFileMouseClick();
            }
        });
        fileBox.add(fileNameTextField);
        fileNamePanel.add(fileBox);
        filePanel.add(fileNamePanel);
        JLabel outputFileAppendLabel = new JLabel("append:");
        filePanel.add(outputFileAppendLabel);
        JCheckBox outputFileAppendCheckBox = new JCheckBox();
        filePanel.add(outputFileAppendCheckBox);
        mOutputFileAppendCheckBox = outputFileAppendCheckBox;
        mOutputFileAppendLabel = outputFileAppendLabel;
        outputFileAppendCheckBox.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                boolean append = mOutputFileAppendCheckBox.isSelected();
                if(! append && null != mOutputFile && mOutputFile.exists())
                {
                    boolean proceed = FileChooser.handleOutputFileAlreadyExists(mLauncherFrame, 
                                                                                mOutputFile.getAbsolutePath());
                    if(! proceed)
                    {
                        mOutputFileAppendCheckBox.setSelected(true);
                    }
                }
            }
        });

        JLabel outputFileFormatListLabel = new JLabel("format:");
        filePanel.add(outputFileFormatListLabel);
        mOutputFileFormatListLabel = outputFileFormatListLabel;
        // create combo box of file formats
        JComboBox outputFileFormatComboBox = new JComboBox(TimeSeriesOutputFormat.getSortedFileFormatNames());
        mOutputFileFormatList = outputFileFormatComboBox;
        outputFileFormatComboBox.setMaximumSize(new Dimension(100, 50));
        filePanel.add(outputFileFormatComboBox);
        
        // set the default output type
        mOutputType = OutputType.PLOT;


        enableOutputFieldSection(false);
        filePanel.setAlignmentX(Container.LEFT_ALIGNMENT);
        filePanel.setMaximumSize(new Dimension(600, 50));
        outputPanel.add(filePanel);

        return(outputPanel);
    }

    private JPanel createModelSymbolLabelPanel()
    {
        JPanel labelPanel = new JPanel();
        labelPanel.setBorder(BorderFactory.createEtchedBorder());
        JLabel modelLabel = new JLabel();
        mModelNameLabel = modelLabel;
        setModelLabel("unknown");
        labelPanel.add(modelLabel);
        return(labelPanel);
    }

    private void setModelLabel(String pModelName)
    {
        mModelNameLabel.setText("model name: [" + pModelName + "]");
    }

    private void createSimulationRunnerThread()
    {
        SimulationRunner simulationRunner = new SimulationRunner();
        Thread simulationRunnerThread = new Thread(simulationRunner);
        simulationRunnerThread.setDaemon(true);
        simulationRunnerThread.start();
        mSimulationRunner = simulationRunner;
    }

    private void createSimulationProgressReporterThread()
    {
        SimulationProgressReportHandler handler = new SimulationProgressReportHandler();
        mSimulationProgressReportHandler = handler;
        Thread simulationProgressReportHandlerThread = new Thread(handler);
        simulationProgressReportHandlerThread.setDaemon(true);
        simulationProgressReportHandlerThread.start();
    }

    private void handleCloseSimulationLauncher()
    {
        handleCancelButton();
        mSimulationRunner.setTerminate(true);
        mSimulationProgressReportHandler.setTerminate(true);
        setLauncherFrame(null);
    }

    private void createLauncherFrame(Class pFrameClass)
    {
        // create the launcher frame
        Component frame = null;
        try
        {
            frame = (Component) pFrameClass.newInstance();
        }
        catch(Exception e)
        {
            throw new IllegalStateException("unable to instantiate class: " + pFrameClass.getName() + " as an AWT container");
        }
        setLauncherFrame(frame);

        String appTitle = mAppName + ": simulator";

        JPanel controllerPanel = new JPanel();
        Box box = new Box(BoxLayout.Y_AXIS);

        JPanel labelPanel = createModelSymbolLabelPanel();
        box.add(labelPanel);

        JProgressBar simulationProgressBar = new JProgressBar(0, 100);
        mSimulationProgressBar = simulationProgressBar;
        simulationProgressBar.setMinimumSize(new Dimension(300, 30));
        JLabel secondsRemainingLabel = new JLabel("secs remaining: ");
        mSecondsRemainingLabel = secondsRemainingLabel;
        JTextField secondsRemainingNumber = new JTextField(DEFAULT_SECONDS_REMAINING_NUMBER_TEXT, 15);
        secondsRemainingNumber.setMaximumSize(new Dimension(100, 30));
        secondsRemainingNumber.setEditable(false);
        mSecondsRemainingNumber = secondsRemainingNumber;

        JPanel midPanel = new JPanel();
        JPanel buttonPanel = createButtonPanel();
        midPanel.add(buttonPanel);
        JPanel startStopTimePanel = createStartStopTimePanel();
        JPanel simulatorsListPanel = createSimulatorsListPanel();
        midPanel.add(simulatorsListPanel);
        midPanel.add(startStopTimePanel);
        JPanel symbolListPanel = createSymbolListPanel();
        midPanel.add(symbolListPanel);

        box.add(midPanel);

        if(mHandleOutputInternally)
        {
            JPanel resultsOutputPanel = new JPanel();
            JPanel resultsPanel = createResultsPanel();
            JPanel outputPanel = createOutputPanel();

            // add the output panel first, so that it appears to the left of
            // the results panel
            resultsOutputPanel.add(outputPanel);
            resultsOutputPanel.add(resultsPanel);
            box.add(resultsOutputPanel);
        }
        else
        {
            mOutputType = null;
            mOutputFileField = null;
            mOutputFileAppendCheckBox = null;
            mOutputFileAppendLabel = null;
        }

        JPanel progressBox = new JPanel();
        progressBox.setLayout(new BoxLayout(progressBox, BoxLayout.X_AXIS));
        progressBox.add(simulationProgressBar);
        progressBox.add(secondsRemainingLabel);
        progressBox.add(secondsRemainingNumber);

        box.add(progressBox);

        controllerPanel.add(box);

        Container contentPane = null;

         // Add listener for "window-close" event
        if(frame instanceof JFrame)
        {
            JFrame myFrame = (JFrame) frame;
            myFrame.setTitle(appTitle);
            contentPane = myFrame.getContentPane();
            contentPane.add(controllerPanel);
            myFrame.addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    handleCloseSimulationLauncher();
                }
            });
        }
        else if(frame instanceof JInternalFrame)
        {
            JInternalFrame myFrame = (JInternalFrame) frame;
            myFrame.addInternalFrameListener(new InternalFrameAdapter() {
                public void internalFrameClosed(InternalFrameEvent e) {
                    handleCloseSimulationLauncher();
                }
            });
            myFrame.setTitle(appTitle);
            contentPane = myFrame.getContentPane();
            contentPane.add(controllerPanel);
        }
        else
        {
            throw new IllegalStateException("unknown container type");
        }

    }

    /**
     * Registers a {@link SimulationLauncher.Listener} object to receive
     * events for this simulation launcher.
     */
    public void addListener(Listener pListener)
    {
        Component frame = getLauncherFrame();
        final Listener listener = pListener;
        mListeners.add(pListener);
        if(frame instanceof JFrame)
        {
            JFrame launcherFrame = (JFrame) frame;
            launcherFrame.addWindowListener(new WindowAdapter() 
            {
                public void windowClosing(WindowEvent e)
                {
                    listener.simulationLauncherClosing();
                }
            }
                );
        }
        else if(frame instanceof JInternalFrame)
        {
            JInternalFrame launcherFrame = (JInternalFrame) frame;
            launcherFrame.addInternalFrameListener(new InternalFrameAdapter()
            {
                public void internalFrameClosing(InternalFrameEvent e)
                {
                    listener.simulationLauncherClosing();
                }
            }
                );
        }
        else
        {
            throw new IllegalStateException("unknown listener component type");
        }
    }

    private void clearSimulationResultsList()
    {
        mResultsList.clear();
        updateSimulationResultsList();
    }

    /**
     * Sets the underlying {@link org.systemsbiology.chem.Model} data structure
     * to be <code>pModel</code>.  The possible results are the enumerated class
     * {@link SimulationLauncher.SetModelResult}.
     */
    public SetModelResult setModel(Model pModel)
    {
        SetModelResult result = null;

        if(! getSimulationInProgress())
        {
            if(null != getLauncherFrame())
            {
                // set the model

                mModel = pModel;
                clearSimulationResultsList();
                mSimulatorRegistry.clearInstances();
                populateSymbolListPanel();
                setModelLabel(pModel.getName());

                result = SetModelResult.SUCCESS;
            }
            else
            {
                result = SetModelResult.FAILED_CLOSED;
            }
        }
        else
        {
            result = SetModelResult.FAILED_RUNNING;
        }

        return(result);
    }


    /**
     * Brings the SimulationLauncher frame "to the front".  Does not
     * necessarily transfer focus to the SimulationLauncher (that depends 
     * on the window manager).
     */
    public void toFront()
    {
        Component launcherFrame = mLauncherFrame;
        if(launcherFrame instanceof JFrame)
        {
            ((JFrame) launcherFrame).toFront();
        }
        else if(launcherFrame instanceof JInternalFrame)
        {
            ((JInternalFrame) launcherFrame).toFront();
        }
        else
        {
            assert false : "unknown internal frame type";
        }
    }

    /**
     * Returns the next {@link org.systemsbiology.chem.SimulationResults} object in the queue.
     * If the queue is empty, null is returned.
     */
    public SimulationResults getNextResults() throws IllegalStateException
    {
        if(mHandleOutputInternally)
        {
            throw new IllegalStateException("cannot access getNextResults() if HandleOutputInternally flag was passed to the SimulationLauncher constructor");
        }

        return((SimulationResults) mResultsQueue.getNext());
    }
}
    
