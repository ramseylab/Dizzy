/*
 * Copyright (C) 2004 by Institute for Systems Biology,
 * Seattle, Washington, USA.  All rights reserved.
 * 
 * This source code is distributed under the GNU Lesser
 * General Public License, the text of which should have
 * been distributed with this source code in the file 
 * License.html.  The license can also be obtained at:
 *   http://www.gnu.org/copyleft/lesser.html
 */
package org.systemsbiology.pointillist;

import org.systemsbiology.gui.*;
import java.awt.*;
import javax.swing.*;

/**
 * @author sramsey
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
public class MatlabConnectAction implements IAction
{
    private App mApp;
    
    public MatlabConnectAction(App pApp)
    {
        mApp = pApp;
    }
    
    public void doAction()
    {
        MatlabConnectionManager mgr = mApp.getMatlabConnectionManager();
        String matlabLocation = mgr.getMatlabLocation();
        if(matlabLocation.length() == 0)
        {
            JOptionPane.showMessageDialog(mApp,
                                          "no matlab location is defined; please use the Preferences option from the Edit menu",
                                          "no matlab location is defined",
                                          JOptionPane.WARNING_MESSAGE);
            return;
        }
        String matlabScriptsLocation = mgr.getMatlabScriptsLocation();
        if(matlabScriptsLocation.length() == 0)
        {
            JOptionPane.showMessageDialog(mApp,
                                          "no matlab scripts location is defined; please use the Preferences option from the Edit menu",
                                          "no matlab scripts location is defined",
                                          JOptionPane.WARNING_MESSAGE);
            return;
        }
        if(mgr.isConnected())
        {
            return;
        }
        
        mApp.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        try
        {
            mgr.connect();
        }
        catch(Exception e)
        {
            mApp.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            ExceptionNotificationOptionPane optionPane = new ExceptionNotificationOptionPane(e);
            optionPane.createDialog(mApp, "unable to create matlab session").show();
            return;
        }
        mApp.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        mApp.handleMatlabConnectionState(true);
    }
}
