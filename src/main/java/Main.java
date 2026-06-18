import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
        while(true)
            {
                System.out.print("$ ");

                Scanner scanner = new Scanner(System.in);
                String command = scanner.nextLine();
                
                if(command.equals("exit"))
                    {
                        break;
                    }
                else if(command.startsWith("echo"))
                    {
                        System.out.print(command.substring(5));
                    }
                else 
                    {
                        System.out.println(command + ": command not found");
                    }
            }
    }
}
