package com.demo.upimesh.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "demo_users", uniqueConstraints = @UniqueConstraint(columnNames = "contact"))
public class DemoUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 254)
    private String contact;
    @Column(nullable = false, length = 80)
    private String displayName;

    public DemoUser() {}
    public DemoUser(String contact, String displayName) {
        this.contact = contact;
        this.displayName = displayName;
    }
    public Long getId() { return id; }
    public String getContact() { return contact; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
