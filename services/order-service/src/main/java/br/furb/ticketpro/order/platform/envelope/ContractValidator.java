package br.furb.ticketpro.order.platform.envelope;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates whole messages against contracts/events/&lt;type&gt;/v&lt;version&gt;.schema.json (RFC
 * 0001 §5.3).
 */
public final class ContractValidator {

  private static final String ID_PREFIX = "https://ticketpro.local/contracts/";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final Map<String, Schema> schemas = new HashMap<>();

  /**
   * Loads the event schemas under {@code contractsDir}. Every $id under {@value #ID_PREFIX}
   * (including the relative $refs to envelope/common) is served from the files, never fetched over
   * the network.
   */
  public ContractValidator(Path contractsDir) {
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            b ->
                b.schemaLoader(l ->
                    l.resourceLoaders(r -> r.resources(iri -> fromContracts(contractsDir, iri)))));
    Path events = contractsDir.resolve("events");
    try (Stream<Path> files = Files.walk(events)) {
      for (Path file :
          files.filter(p -> p.getFileName().toString().endsWith(".schema.json")).toList()) {
        Path rel = events.relativize(file);
        String key =
            rel.getName(0) + "/" + rel.getFileName().toString().replace(".schema.json", "");
        schemas.put(
            key,
            registry.getSchema(
                SchemaLocation.of(ID_PREFIX + "events/" + rel.toString().replace('\\', '/'))));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("load contracts from " + contractsDir, e);
    }
    if (schemas.isEmpty()) {
      throw new IllegalStateException("no event schemas under " + events);
    }
  }

  private static String fromContracts(Path contractsDir, String iri) {
    if (!iri.startsWith(ID_PREFIX)) {
      return null;
    }
    try {
      return Files.readString(contractsDir.resolve(iri.substring(ID_PREFIX.length())));
    } catch (IOException e) {
      throw new UncheckedIOException("contract " + iri, e);
    }
  }

  /**
   * Unknown type/version → {@link UnsupportedVersionException}; violation → {@link
   * InvalidMessageException}.
   */
  public void validate(String rawJson) {
    JsonNode root;
    try {
      root = JSON.readTree(rawJson);
    } catch (JacksonException e) {
      throw new InvalidMessageException("message is not JSON", e);
    }
    if (!root.isObject()) {
      throw new InvalidMessageException("message is not an object");
    }
    JsonNode type = root.get("type");
    JsonNode version = root.get("version");
    String key =
        (type == null ? "?" : type.asString())
            + "/v"
            + (version == null ? "?" : version.asString());
    Schema schema = schemas.get(key);
    if (schema == null) {
      throw new UnsupportedVersionException(key);
    }
    List<Error> errors =
        schema.validate(root, ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)));
    if (!errors.isEmpty()) {
      throw new InvalidMessageException(
          key + ": " + errors.stream().map(Error::toString).collect(Collectors.joining("; ")));
    }
  }
}
