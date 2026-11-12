package dev.appiaworks.shiguchi.sample.jpabackend;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "book")
public class BookEntity {

    @Id
    private String id;

    private String title;
    private String author;

    // The proto field is published_year, the property is year: BookBackend maps one to the other.
    // (The column is named explicitly because YEAR is a reserved word in several databases.)
    @Column(name = "published_year")
    private int year;

    protected BookEntity() {}

    public BookEntity(String id, String title, String author, int year) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.year = year;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public int getYear() {
        return year;
    }
}
