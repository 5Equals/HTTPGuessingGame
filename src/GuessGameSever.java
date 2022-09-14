/**
 * Guessing game browser based, try you luck and guess the random number chosen between 0-100.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

// Server handles the http requests and responses
public class GuessGameSever {
    static Dictionary<Integer, GuessGame> clientGames;

    public static void main(String[] args) {
        try {
            System.out.println("------Starting the guess game server------");
            ServerSocket gameServer = new ServerSocket(4242);
            ClientHandler clientHandler = new ClientHandler();
            clientGames = new Hashtable<Integer, GuessGame>();
            while (true) {
                Socket client = gameServer.accept();
                clientHandler.handleClient(client, clientGames);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}

// The handler responsible to server the client that play the game.
class ClientHandler {
    // handle the clients requests.
    void handleClient(Socket clientSocket, Dictionary<Integer, GuessGame> clientGames){
        try {
            clientSocket.setSoTimeout(10000);
            System.out.println("Connection received from: " + clientSocket.toString());
            InputStream fromClient = clientSocket.getInputStream();
            OutputStream toClient = clientSocket.getOutputStream();

            String outData = readHTTPRequest(fromClient);
            byte[] HTTPResponseToSend = processHTTPRequest(outData, clientGames);

            if (HTTPResponseToSend != null) {
                toClient.write(HTTPResponseToSend);
            }

            closeConnection(clientSocket, fromClient, toClient);
        } catch (IOException ioException) {
            System.out.println("Something went wrong with the socket");
            ioException.printStackTrace();
            closeConnection(clientSocket, null, null);
        }
    }

    // Closes the connection to the socket and their in and out streams.
    void closeConnection(Socket socket, InputStream inStream, OutputStream outStream) {
        try {
            socket.close();
            if (inStream != null) {
                inStream.close();
            }
            if (outStream != null) {
                outStream.close();
            }
        } catch (IOException ioException) {
            System.out.println("Something went wrong with the socket");
            ioException.printStackTrace();
        }
    }

    // Get the request from the client. Using chunks of 32KB.
    String readHTTPRequest(InputStream fromClient) throws IOException {
        int maxBufferSizePerIteration = 32 * 1024;
        StringBuilder requestData = new StringBuilder();

        while (true) {
            byte[] data = new byte[maxBufferSizePerIteration];
            int recvLen = fromClient.read(data);
            String response;
            if (recvLen != -1) {
                response = new String(data, 0, recvLen, StandardCharsets.UTF_8);
                if (response.contains("\r\n\r\n")) // Check if the web browser sent an empty line
                {
                    requestData.append(response);
                    break;
                } else { // Append data to the string builder and keep reading
                    response = new String(data, 0, recvLen, StandardCharsets.UTF_8);
                    requestData.append(response);
                }
            } else { // No data left to read
                break;
            }
        }

        return requestData.toString();
    }

    // Check the client request and direct them for the right page.
    byte[] processHTTPRequest(String requestData, Dictionary<Integer, GuessGame> clientGames) {
        byte[] HTTPResponse;

        if (requestData.startsWith("GET /")) {
            if (requestData.startsWith("GET /favicon.ico")) {
                HTTPResponse = constructNotFound();
            } else if (requestData.startsWith("GET /index.html")) {
                HTTPResponse = processDefaultPage(requestData, clientGames);
            } else if (requestData.startsWith("GET /guess.html?")) {
                HTTPResponse = processGuessPage(requestData, clientGames);
            }else if (requestData.startsWith("GET /success.html")) {
                HTTPResponse = processSuccessPage(requestData, clientGames);
            } else if (requestData.startsWith("GET /new.html")) {
                HTTPResponse = processNewGamePage(requestData, clientGames);
            } else {
                HTTPResponse = processDefaultPage(requestData, clientGames);
            }
        } else {
            HTTPResponse = constructBadRequest();
        }

        return HTTPResponse;
    }

    // Check if the client has a cookie or not.
    boolean checkGameCookie(String requestData) {
        if (requestData.contains("Cookie: ") && requestData.contains("clientId=")) {
            return true;
        }
        return false;
    }

    // Get the client cookie while keeping in mind that the client has one.
    int getClientIdCookie(String requestData) {
        String clientId = requestData.split("clientId=")[1].split("\r\n")[0];
        return Integer.parseInt(clientId);
    }

    // Generate a game for the new clients and assigning them an id.
    int generateGame(Dictionary<Integer, GuessGame> clientGames) {
        int id = 1;
        if (clientGames.size() != 0) {
            List<Integer> list = new ArrayList<Integer>();
            clientGames.keys().asIterator().forEachRemaining(list::add);
            id = Collections.max(list) + 1;
        }
        GuessGame game = new GuessGame(id);
        clientGames.put(id, game);
        return id;
    }

    // Get the guess that the client has given.
    private int extractUserGuess(String requestData) {
        try {
            String[] splitData = requestData.split("guessedNumber=");
            String result = splitData[1].split(" ")[0];
            return Integer.parseInt(result);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException nfex) {
            return -1;
        }
    }

    // Check if the game exists for the client, in case not it creates one.
    void gameCreatorChecker(int clientId, Dictionary<Integer, GuessGame> clientGames) {
        if (clientGames.get(clientId) == null) {
            clientGames.put(clientId, new GuessGame(clientId));
        }
    }

    // Direct the client for the index page.
    byte[] processDefaultPage(String requestData, Dictionary<Integer, GuessGame> clientGames) {
        String defaultPage = readPage("index.html");
        boolean cookieCheck = checkGameCookie(requestData);
        byte[] responseData;
        int clientId = -1;

        if (cookieCheck) {
            clientId = getClientIdCookie(requestData);
            gameCreatorChecker(clientId, clientGames);
            responseData = constructHTTPPage(defaultPage);
        } else {
            clientId = generateGame(clientGames);
            responseData = constructHTTPPageCookie(defaultPage, clientId);
        }

        return responseData;
    }

    // Direct the client for the guess page.
    byte[] processGuessPage(String requestData, Dictionary<Integer, GuessGame> clientGames) {
        String guessPage = readPage("guess.html");
        int userGuess = extractUserGuess(requestData);
        int[] guessRes = new int[2];
        byte[] responseData;

        boolean cookieCheck = checkGameCookie(requestData);
        int clientId = -1;

        if (cookieCheck) {
            clientId = getClientIdCookie(requestData);
            String guessHOL = "";
            gameCreatorChecker(clientId, clientGames);

            // In case that the client given an invalid value.
            if (userGuess != -1) {
                guessRes = clientGames.get(clientId).checkGuess(userGuess);

                if (guessRes[0] == 0) {
                    return constructRedirect();
                } else if (guessRes[0] == -1) {
                    guessHOL = "higher";
                } else if (guessRes[0] == 1) {
                    guessHOL = "lower";
                }
            } else {
                guessHOL = "a valid input";
                guessRes[1] = clientGames.get(clientId).getGuessCount();
            }

            guessPage = guessPage.replace("PLACEHOLDER1", guessHOL);
            guessPage = guessPage.replace("PLACEHOLDER2", "" + guessRes[1]);
            responseData = constructHTTPPage(guessPage);
        } else {
            clientId = generateGame(clientGames);
            responseData = constructHTTPPageCookie(guessPage, clientId);
        }

        return responseData;
    }

    // Direct the client to the success page.
    byte[] processSuccessPage(String requestData, Dictionary<Integer, GuessGame> clientGames) {
        String successPage = readPage("success.html");
        byte[] responseData;

        boolean cookieCheck = checkGameCookie(requestData);
        int clientId = -1;

        if (cookieCheck) {
            clientId = getClientIdCookie(requestData);
            gameCreatorChecker(clientId, clientGames);
            int guessCount = clientGames.get(clientId).getGuessCount();
            successPage = successPage.replace("PLACEHOLDER3", "" + guessCount);
            responseData = constructHTTPPage(successPage);
        } else {
            clientId = generateGame(clientGames);
            responseData = constructHTTPPageCookie(successPage, clientId);
        }

        return responseData;
    }

    // Direct the client to the new game page.
    byte[] processNewGamePage(String requestData, Dictionary<Integer, GuessGame> clientGames) {
        String defaultPage = readPage("index.html");
        byte[] responseData;

        boolean cookieCheck = checkGameCookie(requestData);
        int clientId = -1;

        if (cookieCheck) {
            clientId = getClientIdCookie(requestData);
            gameCreatorChecker(clientId, clientGames);
            clientGames.put(clientId, new GuessGame(clientId));
            responseData = constructHTTPPage(defaultPage);
        } else {
            clientId = generateGame(clientGames);
            clientGames.put(clientId, new GuessGame(clientId));
            responseData = constructHTTPPageCookie(defaultPage, clientId);
        }

        return responseData;
    }

    // Build the http response with a cookie for the client.
    byte[] constructHTTPPageCookie(String data, int clientId) {
        StringBuilder dataToSend = new StringBuilder();
        dataToSend.append("HTTP/1.1 200 OK\r\n");
        dataToSend.append("Server: GuessGame 1.0\r\n");
        dataToSend.append("Content-Type: text/html; charset=UTF-8\r\n");
        dataToSend.append("Cache-Control: no-cache, no-store, must-revalidate\r\n");
        dataToSend.append("Pragma: no-cache\r\n");
        dataToSend.append("Expires: 0\r\n");
        dataToSend.append(String.format("Set-Cookie: clientId=%d;", clientId));
        dataToSend.append("\r\n");
        dataToSend.append("\r\n");
        dataToSend.append(data);

        return dataToSend.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Build the http response for the client that has already a cookie.
    byte[] constructHTTPPage(String data) {
        StringBuilder dataToSend = new StringBuilder();
        dataToSend.append("HTTP/1.1 200 OK\r\n");
        dataToSend.append("Server: GuessGame 1.0\r\n");
        dataToSend.append("Content-Type: text/html; charset=UTF-8\r\n");
        dataToSend.append("Cache-Control: no-cache, no-store, must-revalidate\r\n");
        dataToSend.append("Pragma: no-cache\r\n");
        dataToSend.append("Expires: 0");
        dataToSend.append("\r\n");
        dataToSend.append("\r\n");
        dataToSend.append(data);

        return dataToSend.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Build the http response for the invalid http requests.
    byte[] constructBadRequest() {
        StringBuilder dataToSend = new StringBuilder();
        dataToSend.append("HTTP/1.1 400 Bad Request\r\n");
        dataToSend.append("Cache-Control: no-cache, no-store, must-revalidate\r\n");
        dataToSend.append("Pragma: no-cache\r\n");
        dataToSend.append("Expires: 0");
        dataToSend.append("\r\n");
        dataToSend.append("\r\n");

        return dataToSend.toString().getBytes(StandardCharsets.UTF_8);
    }

    byte[] constructNotFound() {
        StringBuilder dataToSend = new StringBuilder();
        dataToSend.append("HTTP/1.1 404 Not Found\r\n");
        dataToSend.append("Cache-Control: no-cache, no-store, must-revalidate\r\n");
        dataToSend.append("Pragma: no-cache\r\n");
        dataToSend.append("Expires: 0");
        dataToSend.append("\r\n");
        dataToSend.append("\r\n");

        return dataToSend.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Build the http response for the redirection to the success page.
    byte[] constructRedirect() {
        StringBuilder dataToSend = new StringBuilder();
        dataToSend.append("HTTP/1.1 301 Moved Permanently\r\n");
        dataToSend.append("Location: success.html\r\n");
        dataToSend.append("Cache-Control: no-cache, no-store, must-revalidate\r\n");
        dataToSend.append("Pragma: no-cache\r\n");
        dataToSend.append("Expires: 0");
        dataToSend.append("\r\n");
        dataToSend.append("\r\n");
        return dataToSend.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Read the data from the page file.
    private String readPage(String filePath) {
        Path pagePath = Paths.get(filePath);
        try {
            String data = Files.readString(pagePath);
            return data;
        } catch (Exception ex) {
            ex.printStackTrace();
            return "";
        }
    }
}

// Game logic for each client
class GuessGame {
    private int clientId;
    private int guessCount;
    private int correctGuess;

    GuessGame(int clientId) {
        this.clientId = clientId;
        this.guessCount = 0;
        Random rand = new Random();
        correctGuess = rand.nextInt(101);
    }

    // Check if the client guess matches the random game number.
    // if the client guess is smaller returns -1, if equals returns 0 and if is bigger returns 1.
    int[] checkGuess(int guess) {
        this.guessCount++;
        int equalRes = 0;
        int[] res = new int[2];

        if (guess < this.correctGuess) {
            equalRes = -1;
        }
        if (guess > this.correctGuess) {
            equalRes = 1;
        }

        res[0] = equalRes;
        res[1] = this.guessCount;
        return res; // [Equality check, number of guesses so far]
    }

    int getGuessCount() {return this.guessCount;}
}
