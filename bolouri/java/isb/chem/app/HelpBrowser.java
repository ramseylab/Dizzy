package isb.chem.app;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.net.URL;
import java.io.File;
import javax.swing.event.*;
import javax.swing.text.html.*;
import isb.util.*;
import javax.help.*;

public class HelpBrowser
{
    private static final int WIDTH = 630;
    private static final int HEIGHT = 480;
    private static final String TOP_MAP_ID = "top";

    JFrame mMainFrame;
    private static final String HELP_PACKAGE_FILE_NAME = "html/AppHelp.jar";

    public HelpBrowser(JFrame pMainFrame)
    {
        mMainFrame = pMainFrame;
    }


    public void displayHelpBrowser()
    {
        try
        {
            MainApp theApp = MainApp.getApp();

            File appDir = theApp.getAppDir();
            String helpSetName = theApp.getAppConfig().getAppHelpSetName();

            if(helpSetName != null && helpSetName.length() > 0)
            {

                URL helpPackageURL = HelpSet.findHelpSet(null, helpSetName);
                if(null != helpPackageURL)
                {
                    HelpSet hs = new HelpSet(null, helpPackageURL);
                    HelpBroker hb = hs.createHelpBroker();
                    hb.setCurrentID(TOP_MAP_ID);
                    hb.setDisplayed(true);
                }
                else
                {
                    SimpleDialog notFoundDialog = new SimpleDialog(mMainFrame, "Help file not found", 
                                                                   "The help file was not found: " + helpSetName);
                    notFoundDialog.show();
                }
            }
            else
            {
                    SimpleDialog notFoundDialog = new SimpleDialog(mMainFrame, "No help is available", 
                                                                   "Sorry, no on-line help is available");
                    notFoundDialog.show();
            }
        }
        catch(Exception e)
        {
            ExceptionDialogOperationCancelled exceptionDialog = new ExceptionDialogOperationCancelled(mMainFrame,
                                                                                                      "Error displaying online help",
                                                                                                      e);
            exceptionDialog.show();
            return;
        }        
    }
}
