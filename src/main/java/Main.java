import java.util.*;
import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
        while(true)
            {
                System.out.print("$ ");

                Scanner scanner = new Scanner(System.in);
                String command = scanner.nextLine();
                String name = command.substring(5);
                
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
                    
                        if(name.equals("echo") || name.equals("exit") || name.equals("type"))
                        {
                            System.out.println(command.substring(5) + " is a shell builtin");
                        }
                        {
                    // System.out.println(command + ": command not found");
                    String path = System.getenv("PATH");
                    String[] folders = path.split(File.pathSeparator);

                    boolean found = false;

                    for(String folder : folders)
                    {
                        File file = new File(folder, name);

                        if(file.exists() && file.isFile() && file.canExecute())
                        {
                            System.out.println(name+ " is " + file.getPath());
                            found=true;
                            break;
                        }
                    }
                    if(!found)
                    {
                        System.out.println(name = ": not found");
                    }
                }
                        
                    
                }
            }
    }
}
