package archipelago.network.shell;

import archipelago.FijiArchipelago;
import archipelago.exception.ShellExecutionException;
import archipelago.listen.ShellExecListener;
import archipelago.network.node.NodeManager;
import archipelago.util.EasyLogger;
import archipelago.util.InputStreamLogger;
import com.jcraft.jsch.*;
import ij.IJ;
import ij.gui.GenericDialog;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;

public class JSchNodeShell implements NodeShell
{

    private static class JSNUserInfo implements UserInfo
    {

        private String passphrase = "";
        private boolean displayEnabled = true;
        private boolean passSet = false;

        public String getPassphrase() {
            return passphrase;
        }

        public String getPassword() {
            return null;
        }

        public boolean promptPassword(String s) {
            return true;
        }

        public boolean promptPassphrase(String s) {
            if (displayEnabled && !passSet)
            {
                GenericDialog gd = new GenericDialog("Enter Passphrase");

                gd.addStringField("Please enter the public key passphrase", "");

                gd.showDialog();

                passphrase = gd.getNextString();

                passSet = true;
            }
            return true;
        }

        public boolean promptYesNo(String s) {
            return true;
        }

        public void showMessage(String s)
        {
            if (displayEnabled)
            {
                IJ.showMessage(s);
            }
        }

        public void enableDisplay()
        {
            displayEnabled = true;
        }

        public void disableDisplay()
        {
            displayEnabled = false;
        }

        public void unsetPass()
        {
            passSet = false;
        }

    }

    public static class JSchShellParams
    {
        private int port;
        private UserInfo ui;
        private JSch jsch;
        
        public JSchShellParams(File f)
        {
            this(f, 22);
        }
        
        public JSchShellParams(File f, int p)
        {
            port = p;
            ui = new JSNUserInfo();
            jsch = new JSch();
            addKey(f);
        }
        
        public boolean addKey(File f)
        {
            try
            {
                jsch.addIdentity(f.getAbsolutePath());
                return true;
            }
            catch (JSchException jse)
            {
                return false;
            }
        }
        
        public int getPort()
        {
            return port;
        }
        
        public UserInfo getUserInfo()
        {
            return ui;
        }
        

        
        public JSch getJsch()
        {
            return jsch;
        }
    }

    private class JSchShellExecThread extends Thread
    {
        private final long node;
        private final Channel channel;
        private final ShellExecListener listener;
        private final Session session;


        public JSchShellExecThread(long id, Channel c, Session s, ShellExecListener l)
        {
            node = id;
            channel = c;
            listener = l;
            session = s;
        }

        public void run()
        {
            String host = "";
            try
            {
                host = channel.getSession().getHost();

                FijiArchipelago.debug(host + ": connecting channel");
                channel.connect();

                FijiArchipelago.debug(host + ": connect() returned");

                while (!channel.isClosed())
                {
                    Thread.sleep(1000);
                }

                FijiArchipelago.debug(host + ": channel closed");

                listener.execFinished(node, null, channel.getExitStatus());
            }
            catch (JSchException jse)
            {
                FijiArchipelago.debug(host + ": JSchException: " + jse);
                listener.execFinished(node, jse, -1);
            }
            catch (InterruptedException ie)
            {
                FijiArchipelago.debug(host + ": Interrupted");
                listener.execFinished(node, ie, -1);
            }

            channel.disconnect();
            session.disconnect();
        }

    }
    
    private final JSchShellParams params;
    private final InputStreamLogger logger;

    public JSchNodeShell(JSchShellParams p, EasyLogger l)
    {
        params = p;
        logger = new InputStreamLogger(l);
    }
    
    public boolean exec(final NodeManager.NodeParameters param, final String command, final ShellExecListener listener)
            throws ShellExecutionException
    {
        String user = param.getUser();
        String host = param.getHost();
        int port = param.getPort();
        try
        {
            Session session = params.getJsch().getSession(user, host, port);
            Channel channel;
            
            session.setUserInfo(params.getUserInfo());
            session.connect();
            
            channel = session.openChannel("exec");
            ((ChannelExec)channel).setCommand(command);
            
            channel.setInputStream(null);
            ((ChannelExec)channel).setErrStream(System.err);
            
            try
            {
                logger.setStream(channel.getInputStream());
            }
            catch (IOException ioe)
            {
                logger.setStream(null);
            }
            
            new JSchShellExecThread(param.getID(), channel, session, listener).start();
            
            return true;
        }
        catch (JSchException jse)
        {
            if (jse.getMessage().equals("Auth cancel"))
            {
                throw new ShellExecutionException("Authentication failed on " + host, jse);
            }
            else if(jse.getCause() != null && jse.getCause() instanceof UnknownHostException)
            {
                throw new ShellExecutionException("Unknown host " + host, jse);
            }

            throw new ShellExecutionException(jse);
        }
        
    }
}
