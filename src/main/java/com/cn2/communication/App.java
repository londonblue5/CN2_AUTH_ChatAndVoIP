package com.cn2.communication;

import java.io.IOException;
import java.net.*;
import javax.sound.sampled.*;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.*;
import java.awt.event.*;

public class App extends Frame implements WindowListener, ActionListener {

	/*
	 * Definition of the app's fields
	 */
    static TextField inputTextField;
    static JTextArea textArea;
    static JFrame frame;
    static JButton sendButton;
    static JButton callButton;
    public static Color gray;
    final static String newline = "\n";

    /*
     * initialization of networking variables 
     */
    private static DatagramSocket chatSocket;   
    private static DatagramSocket audioSocket;  
    private static InetAddress remoteIP;
    
    // initialization of ports
    private static int localChatPort  = 12225;
    private static int remoteChatPort = 12226;
    private static int localAudioPort  = 13335;
    private static int remoteAudioPort = 13336;

    /*
     * threads
     */
    private static Thread chatListenerThread;
    private static Thread audioReceiverThread;
    private static Thread audioSenderThread;

    // a boolean to indicate if the call is active
    private static boolean callActive = false;

    // μονοφωνικό κανάλι 8-bit signed PCM με συχνότητα δειγματοληψίας στα 8 kHz
    private static final AudioFormat AUDIO_FORMAT = 
            new AudioFormat(8000.0f, 8 , 1, true, false);

	/**
	 * Construct the app's frame and initialize important parameters
	 */
    public App(String title) {
    	
		/*
		 * 1. Defining the components of the GUI
		 */
    	
		// Setting up the characteristics of the frame
        super(title);
        gray = new Color(254, 254, 254);
        setBackground(gray);
        setLayout(new FlowLayout());
        addWindowListener(this);

		// Setting up the TextField and the TextArea
        inputTextField = new TextField();
        inputTextField.setColumns(20);

		// Setting up the TextArea.
        textArea = new JTextArea(10, 40);
        textArea.setLineWrap(true);
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

		//Setting up the buttons
        sendButton = new JButton("Send");
        callButton = new JButton("Call");


		/*
		 * 2. Adding the components to the GUI
		 */        
        add(scrollPane);
        add(inputTextField);
        add(sendButton);
        add(callButton);

		/*
		 * 3. Linking the buttons to the ActionListener
		 */
        sendButton.addActionListener(this);
        callButton.addActionListener(this);
    }

    
	/**
	 * The main method of the application. It continuously listens for
	 * new messages.
	 */
    public static void main(String[] args) {


		/*
		 * 1. Create the app's window
		 */
        App app = new App("CN2 - AUTH");
        app.setSize(500, 250);
        app.setVisible(true);

        
		/*
		 * 2. 
		 */
        try {
            // initialize the sockets and the IP of the receiver 
            remoteIP    = InetAddress.getByName("192.168.1.71");
            chatSocket  = new DatagramSocket(localChatPort);
            audioSocket = new DatagramSocket(localAudioPort);

            // start thread to listen for chat messages
            chatListenerThread = new Thread(new ChatListener());
            chatListenerThread.start();

        //for debugging 
        } catch (Exception e) {
            e.printStackTrace();
        }

        // keep main thread alive
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

	/**
	 * The method that corresponds to the Action Listener. Whenever an action is performed
	 * (i.e., one of the buttons is clicked) this method is executed. 
	 */
    
    @Override
    public void actionPerformed(ActionEvent e) {

		/*
		 * Check which button was clicked.
		 */    	
        if (e.getSource() == sendButton) {
            // send chat message
            try {
                String message = inputTextField.getText().trim();
                if (!message.isEmpty()) {
                    // Show in local text area
                    textArea.append("Me: " + message + newline);

                    // send via UDP
                    byte[] buf = message.getBytes();
                    DatagramPacket packet = new DatagramPacket(
                        buf,
                        buf.length,
                        remoteIP,
                        remoteChatPort
                    );
                    chatSocket.send(packet);

                    inputTextField.setText("");
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }

        } else if (e.getSource() == callButton) {
            // Start or stop call
            if (!callActive) {
                // Start call
                callActive = true;
                textArea.append("Call started.\n");
                callButton.setText("Hang Up");

                // Start receiving audio
                audioReceiverThread = new Thread(new AudioReceiver());
                audioReceiverThread.start();

                // Start sending audio
                audioSenderThread = new Thread(new AudioSender());
                audioSenderThread.start();

            } else {
                // Hang up
                callActive = false;
                textArea.append("Call ended.\n");
                callButton.setText("Call");
            }
        }
    }

    
    
    /**
     * thread that listens for incoming chat messages.
     */
    static class ChatListener implements Runnable {
        @Override
        public void run() {
            try {
                byte[] buffer = new byte[1024];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    chatSocket.receive(packet);

                    String msg = new String(packet.getData(), 0, packet.getLength());
                    textArea.append("Peer: " + msg + newline);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * thread that receives audio data and plays it through speakers.
     */
    static class AudioReceiver implements Runnable {
        @Override
        public void run() {
            try {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT);
                SourceDataLine speakers = (SourceDataLine) AudioSystem.getLine(info);
                speakers.open(AUDIO_FORMAT);
                speakers.start();

                byte[] buffer = new byte[1024];
                while (callActive) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    audioSocket.receive(packet);
                    speakers.write(packet.getData(), 0, packet.getLength());
                }

                // Cleanup
                speakers.drain();
                speakers.close();

            } catch (LineUnavailableException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * thread that captures audio from microphone and sends it over UDP.
     */
    static class AudioSender implements Runnable {
        @Override
        public void run() {
            TargetDataLine microphone = null;
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
                microphone = (TargetDataLine) AudioSystem.getLine(info);
                microphone.open(AUDIO_FORMAT);
                microphone.start();

                byte[] buffer = new byte[1024];
                while (callActive) {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);

                    DatagramPacket packet = new DatagramPacket(
                        buffer,
                        bytesRead,
                        remoteIP,
                        remoteAudioPort
                    );
                    audioSocket.send(packet);
                }

            } catch (LineUnavailableException | IOException e) {
                e.printStackTrace();
            } finally {
                if (microphone != null) {
                    microphone.drain();
                    microphone.close();
                }
            }
        }
    }

    /*
     * Window events
     */
    @Override
    public void windowActivated(WindowEvent e) {}
    @Override
    public void windowClosed(WindowEvent e) {}
    @Override
    public void windowClosing(WindowEvent e) {
        dispose();
        System.exit(0);
    }
    @Override
    public void windowDeactivated(WindowEvent e) {}
    @Override
    public void windowDeiconified(WindowEvent e) {}
    @Override
    public void windowIconified(WindowEvent e) {}
    @Override
    public void windowOpened(WindowEvent e) {}
}
