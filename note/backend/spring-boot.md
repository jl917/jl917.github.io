# Spring Boot

## 프로젝트 초기화

https://start.spring.io/

### Dependencies

- H2 Database
- Lombok
- Spring Web
- Spring Data JPA
- Spring Boot DevTools

GENERATE 버튼을 클릭해서 실행 파일이 담긴 zip 파일을 다운로드 후 압축해제후 ide에서 열기

## API Interface 만들기

```java
// src/main/java/blog/HelloWorldApplication.java
package blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HelloWorldApplication {

	public static void main(String[] args) {
		SpringApplication.run(HelloWorldApplication.class, args);
	}

}
```

```java
// src/main/java/blog/HelloWorldController.java
package blog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloWorldController {
    @GetMapping("helloWorld")
    public String helloWorld(){
        return "Hello World26";
    }
}

```

이후 HelloWorldApplication 실행하고 http://localhost:8080/helloWorld 접속.

## properties로 SpringBoot기본 설정

```sh
# src/main/resources/application.properties
server.port=8081
server.servlet.context-path=/hello-world
```

접근 방법: http://localhost:8081/hello-world/helloWorld

## yaml SpringBoot기본 설정

```sh
# src/main/resources/application.yaml
server:
  port: 8082
  servlet:
    context-path: /hello-world-new
```

접근 방법: http://localhost:8082/hello-world-new/helloWorld

## SpringBoot Rest Interface

- GET RequestParam으로 url params가져오기
- POST/PUT/DELETE RequestParam, RequestBody로 데이터 받기

RequestMapping interface로 GET/POST/DELETE/PUT Mapping을 대체 가능.

```java
// src/main/java/blog/TestRestController.java
package blog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestRestController {

    private static String studentName_1 = "tom";
    private static String studentName_2 = "kitty";

    // @GetMapping("/student")
    @RequestMapping(value = "/student", method = RequestMethod.GET)
    public String getStudent(@RequestParam String id){
        if("1".equals(id)) {
            return studentName_1;
        }else{
            return studentName_2;
        }
    }

    // @PostMapping("/student")
    @RequestMapping(value = "/student", method = RequestMethod.POST)
    public String addStudent(@RequestParam String id, @RequestParam String name){
        if("1".equals(id)) {
            studentName_1 = name;
        }else{
            studentName_2 = name;
        }
        return "ok";
    }

    // @PutMapping("/student")
    @RequestMapping(value = "/student", method = RequestMethod.PUT)
    public String updateStudent(@RequestParam String id, @RequestParam String name){
        if("1".equals(id)) {
            studentName_1 = name;
        }else{
            studentName_2 = name;
        }
        return "ok";
    }

    // @DeleteMapping("/student")
    @RequestMapping(value = "/student", method = RequestMethod.DELETE)
    public String deleteStudent(@RequestParam String id){
        if("1".equals(id)) {
            studentName_1 = null;
        }else{
            studentName_2 = null;
        }
        return "ok";
    }
}
```

## SpringBoot Interface Permission

```java
dependencies {
  // 두건 추가
	implementation 'org.springframework.boot:spring-boot-starter-security'
	testImplementation 'org.springframework.security:spring-security-test'
}
```

```java
// src/main/java/blog/SecurityConfig.java

package blog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String ROLE = "ADMIN";

    @Bean
    public UserDetailsService userDetailsService() {

        InMemoryUserDetailsManager manager =
                new InMemoryUserDetailsManager();

        manager.createUser(
                User.withUsername("aaa")
                        .password(
                                PasswordEncoderFactories
                                        .createDelegatingPasswordEncoder()
                                        .encode("bbb"))
                        .authorities(ROLE)
                        .build());

        return manager;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .authorizeHttpRequests(auth -> auth

                        .requestMatchers("/helloWorld").permitAll()

                        .requestMatchers("/helloWorld1").hasAuthority(ROLE)

                        .anyRequest().authenticated())

                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

}
```

```java
// src/main/java/blog/HelloWorldController.java

package blog;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloWorldController {
    @GetMapping("helloWorld")
    public String helloWorld(){
        return "Hello World26";
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping("/helloWorld1")
    public String helloWorld1() {
        return "Hello World1!";
    }
}

```