This file contains notes pertaining to the source archive
for the Dizzy project, mostly of interest to developers. 

There is a separate Overview.xml file that generates the HTML document
"Overview.html" that is intended for end-users to read.  The "Overview.xml"
file is archived in the "docs" subdirectory.  If you are interested
in *using* Dizzy, you should probably start reading that document,
which can be found on the web at:

  http://projects.systemsbiology.net/magnet/software/Dizzy/docs/Overview.html

Ant is used as the driver for application builds.  You can change to
the CVSROOT/bolouri/apps/Dizzy directory and do an "ant build", but
first you will need to do an "ant build" in the
CVSROOT/bolouri/java directory, to build the ISBJava.jar file.

InstallAnywhere is used to produce an executable "installer" program
that contains the Dizzy application software.  The configuration metadata
that tells InstallAnywhere what files to bundle up in the installer, is
called "project_iap.xml".  This file is *generated* from a template file
using Ant filter tokens.  The template file is this:

   CVSROOT/bolouri/apps/Dizzy/config/project_iap.xml

This file is copied, with Ant filtering enabled, to the following 
file:

   CVSROOT/bolouri/apps/Dizzy/build/project_iap.xml

(the "build" directory is created, if it does not already exit).

Then, InstallAnywhere is invoked, with the "build/project_iap.xml"
filename passed to it on the command-line.

The reason why this baroque system is needed, is because we do not
have the version of InstallAnywhere that has the "source path management"
feature, that allows different users to perform builds with the
same InstallAnywhere project file.  Instead, we use the Ant filter token
mechanism to substitute the path of the builder (user's) CVS enlistment
(sandbod) into the project_iap.xml file.  So the following string in the
InstallAnywhere project file,

    @GLOBAL_ROOT@/java/extlib

would get translated (by the Ant filter token mechanism) to:

    /PATH_TO_USERS_CVS_SANDBOX/bolouri/java/extlib

where "/PATH_TO_USERS_CVS_SANDBOX" is the absolute path to the user's
CVS sandbox for the Bolouri CVS archive, where "user" is the username
of the person who is performing the build.

At some point we should change this system-- we should purchase the
Enterprise version of InstallAnywhere, and start using the
"source path management" feature, and get rid of the cumbersome
Ant filter tokens in the InstallAnywhere project file.




