namespace TicketPro.Platform.Tests;

/// <summary>Locates the repository's contracts/ (source of truth); these tests are not vendored.</summary>
internal static class Repo
{
    public static string ContractsDir { get; } = Find();

    private static string Find()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "contracts");
            if (File.Exists(Path.Combine(candidate, "envelope.schema.json")) && Directory.Exists(Path.Combine(dir.FullName, "shared")))
            {
                return candidate;
            }
        }
        throw new DirectoryNotFoundException("contracts/ not found above " + AppContext.BaseDirectory);
    }
}
