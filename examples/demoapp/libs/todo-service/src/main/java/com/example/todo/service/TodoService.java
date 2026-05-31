package com.example.todo.service;

import com.example.todo.model.Todo;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory Todo service. This CDI bean lives in its own library module, demonstrating
 * cross-library dependency (depends on todo-model).
 */
@ApplicationScoped
public class TodoService {

  private final List<Todo> todos = Collections.synchronizedList(new ArrayList<>());
  private final AtomicLong idSequence = new AtomicLong(1);

  public List<Todo> listAll() {
    return List.copyOf(todos);
  }

  public Optional<Todo> findById(Long id) {
    return todos.stream().filter(t -> t.getId().equals(id)).findFirst();
  }

  public Todo create(String title) {
    Todo todo = new Todo(idSequence.getAndIncrement(), title, false);
    todos.add(todo);
    return todo;
  }

  public Optional<Todo> complete(Long id) {
    return findById(id)
        .map(
            todo -> {
              todo.setCompleted(true);
              return todo;
            });
  }

  public boolean delete(Long id) {
    return todos.removeIf(t -> t.getId().equals(id));
  }
}
