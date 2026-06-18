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
                    // if(command.equals("type echo"))
                    // {
                    //     System.out.println("echo is a shell builtin");
                    // }
                    // else if(command.equals("type exit"))
                    // {
                    //     System.out.println("exit is a shell builtin");
                    // }
                    // else if(command.equals("type type"))
                    // {
                    //     System.out.println("type is a shell builtin");
                    // }
                    if(command.startsWith("type"))
                    {
                        if(command.equals("echo") || command.equals("exit") || command.equals("type"))
                        {
                            System.out.print(command.substring(5) + " is a shell builtin");
                        }
                    }
                    else
                    {
                        System.out.println(command.substring(5)+ ": not found");
                    }
                }
                else 
                {
                    System.out.println(command + ": command not found");
                }
            }
    }
}
