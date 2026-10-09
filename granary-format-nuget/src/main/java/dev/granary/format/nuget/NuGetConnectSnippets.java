package dev.granary.format.nuget;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.List;

/**
 * Ready-to-paste configuration for the NuGet clients: the dotnet CLI, nuget.exe, {@code nuget.config},
 * Paket and Visual Studio. Every client is pointed at the V3 service index of the repository.
 */
final class NuGetConnectSnippets {

    /** The source name shared by all snippets. */
    static final String SOURCE_NAME = "granary";

    private NuGetConnectSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx) {
        String index = ctx.urlWithSlash() + "index.json";
        String username = ctx.username();
        String token = ctx.token();
        return List.of(
                ConnectSnippet.shell("nuget-source", "dotnet: add the package source",
                        "dotnet nuget add source " + index + " -n " + SOURCE_NAME + " -u " + username + " -p " + token
                                + " --store-password-in-clear-text"),
                ConnectSnippet.shell("nuget-push", "dotnet: push a package",
                        "dotnet nuget push ./MyPackage.1.0.0.nupkg -k " + token + " -s " + index),
                ConnectSnippet.file("nuget-config", "nuget.config: source, credentials and API key for a project", "xml",
                        "nuget.config", nugetConfig(index, username, token)),
                ConnectSnippet.shell("nuget-exe-source", "nuget.exe: add the package source",
                        "nuget sources add -Name " + SOURCE_NAME + " -Source " + index + " -UserName " + username
                                + " -Password " + token + " -StorePasswordInClearText"),
                ConnectSnippet.shell("nuget-exe-push", "nuget.exe: push a package",
                        "nuget setapikey " + token + " -Source " + index + "\n"
                                + "nuget push MyPackage.1.0.0.nupkg -Source " + index + " -ApiKey " + token),
                ConnectSnippet.file("paket", "Paket: package source with credentials", "text", "paket.dependencies",
                        "source " + index + " username: \"" + username + "\" password: \"" + token + "\""),
                ConnectSnippet.file("visual-studio", "Visual Studio: add the package source", "text", null,
                        visualStudio(index, username, token)));
    }

    private static String nugetConfig(String index, String username, String token) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<configuration>\n"
                + "  <packageSources>\n"
                + "    <clear />\n"
                + "    <add key=\"" + SOURCE_NAME + "\" value=\"" + index + "\" />\n"
                + "  </packageSources>\n"
                + "  <packageSourceCredentials>\n"
                + "    <" + SOURCE_NAME + ">\n"
                + "      <add key=\"Username\" value=\"" + username + "\" />\n"
                + "      <add key=\"ClearTextPassword\" value=\"" + token + "\" />\n"
                + "    </" + SOURCE_NAME + ">\n"
                + "  </packageSourceCredentials>\n"
                + "  <apikeys>\n"
                + "    <add key=\"" + index + "\" value=\"" + token + "\" />\n"
                + "  </apikeys>\n"
                + "</configuration>";
    }

    private static String visualStudio(String index, String username, String token) {
        return "1. Tools > NuGet Package Manager > Package Manager Settings > Package Sources\n"
                + "2. Click the + button, then set\n"
                + "   Name:   " + SOURCE_NAME + "\n"
                + "   Source: " + index + "\n"
                + "3. Click Update, then OK.\n"
                + "4. When Visual Studio asks for credentials, enter\n"
                + "   User name: " + username + "\n"
                + "   Password:  " + token + "\n"
                + "5. Pick \"" + SOURCE_NAME + "\" as the package source in the NuGet Package Manager window.";
    }
}
