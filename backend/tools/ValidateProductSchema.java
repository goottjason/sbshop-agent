import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

/**
 * Release check against the actual DB, without starting Spring, schedulers or market clients.
 * Compile/run with the release boot JAR's extracted BOOT-INF/lib/* classpath. Pass that lib directory.
 * DB_* credentials stay in the process environment. PostgreSQL sessions are forced read-only;
 * Hibernate only validates existing schema. No Session is opened and no entity is written.
 */
public class ValidateProductSchema {
    public static void main(String[] args) throws Exception {
        System.setProperty("org.jboss.logging.provider", "jdk");
        java.util.logging.Logger.getLogger("org.hibernate").setLevel(java.util.logging.Level.SEVERE);
        String host = required("DB_HOST"), port = required("DB_PORT"), database = required("DB_NAME");
        if (!host.matches("[a-zA-Z0-9._-]+") || !port.matches("[0-9]{1,5}") || !database.matches("[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("Invalid database target");
        var registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
            .applySetting("hibernate.connection.url", "jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?options=-c%20default_transaction_read_only%3Don")
            .applySetting("hibernate.connection.username", required("DB_USERNAME"))
            .applySetting("hibernate.connection.password", required("DB_PASSWORD"))
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .applySetting("hibernate.default_schema", "public")
            .applySetting("hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy")
            .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .build();
        try {
            var sources = new MetadataSources(registry);
            int entities = 0;
            try (var paths = Files.list(Path.of(args[0]))) {
                for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("core-")
                        && p.toString().endsWith(".jar")).toList()) {
                    try (var jar = new JarFile(path.toFile())) {
                        for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")
                                && e.getName().startsWith("com/sbshop/agent/core/")).toList()) {
                            String name = entry.getName().replace('/', '.').replaceFirst("\\.class$", "");
                            Class<?> type = Class.forName(name, false, ValidateProductSchema.class.getClassLoader());
                            if (type.isAnnotationPresent(Entity.class)) { sources.addAnnotatedClass(type); entities++; }
                            if (type.isAnnotationPresent(Converter.class)) sources.addAnnotatedClass(type);
                        }
                    }
                }
            }
            if (entities == 0) throw new IllegalStateException("No release entities found");
            try (var factory = sources.buildMetadata().buildSessionFactory()) {
                System.out.println("READ_ONLY_SCHEMA_VALIDATED entities=" + entities);
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
