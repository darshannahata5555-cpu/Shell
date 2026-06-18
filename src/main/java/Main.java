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
                    System.out.println(command.substring(5));
                    
                }
                else if(command.startsWith("type"))
                {
                    if(command.equals("echo"))
                    {
                        System.out.println("echo is a shell builtin");
                    }
                    else if(command.equals("exit"))
                    {
                        System.out.println("exit is a shell builtin");
                    }
                    else if(command.equals("type"))
                    {
                        System.out.println("type is a shell builtin");
                    }
                    else
                    {
                        System.out.println(command+ ": not found");
                    }
                }
                else 
                {
                    System.out.println(command + ": command not found");
                }
            }
    }
}
