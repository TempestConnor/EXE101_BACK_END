# Mappers

Pick one approach as a team and stick with it — mixing the two per feature is where mapper code turns into archaeology.

MapStruct generates the implementation for you from an annotated interface, which is worth it once mapping logic gets non-trivial:

- Define mapper interfaces with the `@Mapper` annotation, and use `@Mapping` for any field that doesn’t line up by name.
- `componentModel = "spring"` so Spring manages the generated instance for you.
- Suffix the interface `Mapper` (`UserMapper`), and keep method names direction-obvious (`toDto`, `toEntity`).

```java
@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(source = "email", target = "emailAddress")
    UserDTO toDto(User user);
    @Mapping(source = "emailAddress", target = "email")
    User toEntity(UserDTO userDto);
}
```

In tests, `Mappers.getMapper(UserMapper.class)` gets you an instance without a Spring context.

Static mappers trade codegen for something you can read top to bottom without an IDE plugin. Each feature gets two of them, one per representation boundary:

| Class | Converts | Used by |
| --- | --- | --- |
| `<Feature>Mapper` | DTO ↔ model | controller |
| `<Feature>EntityMapper` | model ↔ entity | service |

Make it a `public final` class with a private constructor that throws — nobody should ever be able to instantiate it: `throw new UnsupportedOperationException("This class should never be instantiated");`.

Name the static methods for the direction they map (`toModel`, `toEntity`, `toDto`, `fromCreateDto`, `fromUpdateDto`), guard against null at the top of every method, and build into a local variable before returning it rather than passing a builder chain straight into another call. A mapper’s whole job is copying fields — no I/O, no calling a service or repository from inside one.

```java
public final class UserMapper {
    private UserMapper() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    public static UserDTO toDto(User user) {
        if (user == null) {
            return null;
        }
        UserDTO dto = new UserDTO(user.getId(), user.getEmail());

        return dto;
    }

    public static User toEntity(UserDTO userDto) {
        if (userDto == null) {
            return null;
        }
        User user = User.builder()
            .withId(userDto.id())
            .withEmail(userDto.email())
            .build();

        return user;
    }
}
```
