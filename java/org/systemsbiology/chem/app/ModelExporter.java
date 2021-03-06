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
import java.awt.*;
import javax.swing.*;
import java.io.*;

public class ModelExporter
{
    private Component mMainFrame;

    public ModelExporter(Component pMainFrame)
    {
        mMainFrame = pMainFrame;
    }

    public void exportModel(String pAlias, Model pModel, ClassRegistry pModelExporterRegistry) throws DataNotFoundException
    {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setApproveButtonText("export");
        fileChooser.setDialogTitle("Please specify the file to export");
        fileChooser.setCurrentDirectory(MainApp.getApp().getCurrentDirectory());
        fileChooser.showSaveDialog(mMainFrame);
        File outputFile = fileChooser.getSelectedFile();
        if(null != outputFile)
        {
            String fileName = outputFile.getAbsolutePath();
            IModelExporter exporter = (IModelExporter) pModelExporterRegistry.getInstance(pAlias);
            String fileRegex = exporter.getFileRegex();
            boolean fileNameMatchesRegex = fileName.matches(fileRegex);
            boolean doExport = false;
            
            if(! fileNameMatchesRegex)
            {
                SimpleTextArea textArea = new SimpleTextArea("Your export file name has a non-standard extension:\n" + fileName + "\nThe preferred extension regex is: " + fileRegex + 
                "\nUsing this file name may make it difficult to load the model in the future.  Are you sure you want to proceed?");
                JOptionPane exportOptionPane = new JOptionPane();
                exportOptionPane.setMessageType(JOptionPane.WARNING_MESSAGE);
                exportOptionPane.setOptionType(JOptionPane.YES_NO_OPTION);
                exportOptionPane.setMessage(textArea);
                exportOptionPane.createDialog(mMainFrame,
                "Non-standard export filename").show();
                Integer response = (Integer) exportOptionPane.getValue();
                if(null != response &&
                        response.intValue() == JOptionPane.YES_OPTION)
                {
                    doExport = true;
                }
                else
                {
                    // do nothing
                }
            }
            else
            {
                doExport = true;
            }
            
            if(doExport && outputFile.exists())
            {
                doExport = FileChooser.handleOutputFileAlreadyExists(mMainFrame,
                        fileName);
            }
            
            if(doExport)
            {
                boolean showSuccessfulDialog = false;
                String shortName = outputFile.getName();
                try
                {
                    FileWriter outputFileWriter = new FileWriter(outputFile);
                    PrintWriter printWriter = new PrintWriter(outputFileWriter);
                    exporter.export(pModel, printWriter);
                    MainApp.getApp().setCurrentDirectory(outputFile.getParentFile());
                    showSuccessfulDialog = true;
                }
                catch(Exception e)
                {
                    ExceptionNotificationOptionPane errorOptionPane = new ExceptionNotificationOptionPane(e);
                    errorOptionPane.createDialog(mMainFrame, "Export operation failed: " + shortName).show();
                    
                }
                if(showSuccessfulDialog)
                {
                    JOptionPane.showMessageDialog(mMainFrame,
                            "The file export operation succeeded.\nThe output was saved in file: " + shortName,
                            "Export was successful",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }
        else
        {
            // do nothing; user pressed the cancel button
        }
    }
}
