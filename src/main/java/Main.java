import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
        while(true)
            {
                System.out.print("$ ");

                Scanner scanner = new Scanner(System.in);
                String command = scanner.nextLine();

                System.out.println(command + ": command not found");
                if(command.equals("exit"))
                {
                    break;
                }
            }
    }
}
