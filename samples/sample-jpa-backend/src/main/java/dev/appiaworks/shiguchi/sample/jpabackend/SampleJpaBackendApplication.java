package dev.appiaworks.shiguchi.sample.jpabackend;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * A gateway in front of a Spring Data JPA backend that honours {@code sort_fields}, {@code
 * read_mask} and {@code page_request} with the {@code shiguchi-backend-support-jpa} helpers.
 *
 * <p>The backend is {@link BookBackend}: read it first. It is about twenty lines of real logic
 * because the allow-listing, tie-breaking, column selection, paging and message mapping live in the
 * library. Everything else here is plumbing so the sample is one module with nothing to start.
 */
@SpringBootApplication
public class SampleJpaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleJpaBackendApplication.class, args);
    }

    /** Seeds the in-memory H2 database. */
    @Bean
    ApplicationRunner seedBooks(BookRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            repository.save(new BookEntity("01", "Dune", "Herbert", 1965));
            repository.save(new BookEntity("02", "Emma", "Austen", 1815));
            repository.save(new BookEntity("03", "Ubik", "Dick", 1969));
            repository.save(new BookEntity("04", "Kim", "Kipling", 1901));
            repository.save(new BookEntity("05", "Heat", "Mann", 2020));
            repository.save(new BookEntity("06", "Beloved", "Morrison", 1987));
            repository.save(new BookEntity("07", "Ulysses", "Joyce", 1922));
            repository.save(new BookEntity("08", "Dracula", "Stoker", 1897));
            repository.save(new BookEntity("09", "Neuromancer", "Gibson", 1984));
            repository.save(new BookEntity("10", "Middlemarch", "Eliot", 1871));
        };
    }
}
