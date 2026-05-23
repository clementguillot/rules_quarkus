package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class Fruit extends PanacheEntity {

  @Column(length = 40, unique = true)
  public String name;

  public Fruit() {}

  public Fruit(String name) {
    this.name = name;
  }
}
