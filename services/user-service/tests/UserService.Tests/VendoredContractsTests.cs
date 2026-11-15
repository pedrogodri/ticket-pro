using TicketPro.Platform;

namespace UserService.Tests;

/// <summary>Every vendored example of the events this service produces or consumes validates against
/// the vendored schemas (RFC 0001 §6).</summary>
public sealed class VendoredContractsTests
{
    private static string ContractsDir()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "contracts");
            if (File.Exists(Path.Combine(candidate, ".vendored.json")))
            {
                return candidate;
            }
        }
        throw new DirectoryNotFoundException("vendored contracts/ not found; run scripts/sync-shared.sh");
    }

    [Fact]
    public void VendoredExamples_AreValid()
    {
        var dir = ContractsDir();
        var validator = new ContractValidator(dir);
        var examples = Directory.GetFiles(Path.Combine(dir, "examples"), "*.json");
        Assert.NotEmpty(examples);
        foreach (var f in examples)
        {
            var ex = Record.Exception(() => validator.Validate(File.ReadAllText(f)));
            Assert.True(ex is null, $"{Path.GetFileName(f)}: {ex?.Message}");
        }
    }
}
