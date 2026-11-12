package dev.appiaworks.shiguchi.sample.jpabackend;

import org.springframework.data.jpa.repository.JpaRepository;

/** Used here only to seed and for writes; the list query goes through {@code ProjectionQuery}. */
public interface BookRepository extends JpaRepository<BookEntity, String> {}
