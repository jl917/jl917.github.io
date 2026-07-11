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

## 시스템 로그

```java
System.out.println("id = " + id);
System.out.println("name = " + name);
```

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
// build.gradle
dependencies {
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

## MySQL연동

```yaml
# src/main/resources/application.yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/hello_world?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: root
    password: 1234
    driver-class-name: com.mysql.cj.jdbc.Driver
# spring:
#   datasource:
#     driver-class-name: org.sqlite.JDBC
#     url: jdbc:sqlite:/Users/abc/hello-world.db // 절대경로
```

```java
// build.gradle
dependencies {
    // 추가
    runtimeOnly 'com.mysql:mysql-connector-j'
    implementation 'org.xerial:sqlite-jdbc:3.50.3.0'
}
```

```java
// src/main/java/blog/StudentDao.java
package blog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class StudentDao {

    private final JdbcTemplate jdbcTemplate;

    public StudentDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> getStudent(String id) {

        return jdbcTemplate.queryForList(
                "SELECT * FROM student WHERE id = ?",
                id
        );
    }

    public void saveStudent(String id, String name) {

        jdbcTemplate.update(
                "INSERT INTO student(id, name) VALUES(?, ?)",
                id,
                name
        );
    }

    public void updateStudent(String id, String name) {
        jdbcTemplate.update(
                "UPDATE student SET name=? WHERE id=?",
                name,
                id
        );
    }

    public void deleteStudent(String id) {

        jdbcTemplate.update(
                "DELETE FROM student WHERE id=?",
                id
        );
    }
}

```

```java
// src/main/java/blog/StudentController.java
package blog;
import blog.StudentDao;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class StudentController {

    private final StudentDao studentDao;

    public StudentController(StudentDao studentDao) {
        this.studentDao = studentDao;
    }

    @GetMapping("/student")
    public Object getStudent(@RequestParam String id) {

        List<Map<String, Object>> list = studentDao.getStudent(id);

        if (list.isEmpty()) {
            return null;
        }

        return list.get(0);
    }

    @PostMapping("/student")
    public String addStudent(
            @RequestParam String id,
            @RequestParam String name) {

        studentDao.saveStudent(id, name);

        return "ok";
    }

    @PutMapping("/student")
    public String updateStudent(
            @RequestParam String id,
            @RequestParam String name) {

        studentDao.updateStudent(id, name);

        return "ok";
    }

    @DeleteMapping("/student")
    public String deleteStudent(
            @RequestParam String id) {

        studentDao.deleteStudent(id);

        return "ok";
    }
}
```

## Validation

- 문법 참고: https://jakarta.ee/specifications/bean-validation/3.0/apidocs/jakarta/validation/constraints/package-summary

```java
// build.gradle
dependencies {
    // 추가
    implementation 'org.springframework.boot:spring-boot-starter-validation'
}
```

```java
// src/main/java/blog/dto/ValidationRequest.java
package blog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ValidationRequest {

    @Max(value = 50, message = "count는 최대 50입니다.")
    @Min(value = 10, message = "count는 최소 10입니다.")
    private int count;

    @NotNull(message = "age는 null일 수 없습니다.")
    private String age;

    @NotBlank(message = "name은 공백일 수 없습니다.")
    private String name;

    @Size(min = 10, max = 20, message = "content는 10~20자여야 합니다.")
    private String content;

    @NotEmpty(message = "remark는 비어 있을 수 없습니다.")
    private String remark;

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public String getAge() {
        return age;
    }

    public void setAge(String age) {
        this.age = age;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
```

```java
// src/main/java/blog/ValidationController.java
package blog;

import blog.dto.ValidationRequest;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class ValidationController {

    @PostMapping("/validation")
    public String validation(
            @Valid ValidationRequest request,
            BindingResult result) {

        if (result.hasErrors()) {
            return result.getFieldError().getDefaultMessage();
        }

        return "Validation Success";
    }
}
```

## MyBatis

Spring Boot에서 DB를 사용하는 방법은 크게 3가지가 있습니다.

1. JdbcTemplate

```java
// SQL을 Java 코드 안에 작성합니다.
jdbcTemplate.queryForList(
    "select * from student where id=?",
    id
);
```

2. MyBatis

```java
// SQL은 XML로 분리됩니다.
Student student = studentMapper.selectStudent(id);
```

```xml
<select id="selectStudent">
    select * from student where id=#{id}
</select>
```

3. JPA

```java
// SQL도 거의 안 씁니다.
studentRepository.findById(id);
```

### 장점

- SQL을 Java 코드에서 분리
- SQL 수정이 쉬움
- 복잡한 JOIN 작성 가능
- Oracle, MySQL, MSSQL 모두 사용
- 대기업에서 아직도 많이 사용

### 적용샘플

```yaml
# application.yaml
# ...
mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true
```

```java
// ...
implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4'
```

```java
// Student.java
package blog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Student implements Serializable {

    private static final long serialVersionUID = -7748312639957930069L;

    private String id;
    private String name;
    private StudentClass studentClass;
    private StudentSchool studentSchool;
    private Teacher teacher;
    private List<SchoolBag> schoolBags;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentClass {
        private static final long serialVersionUID = -5213219387175188213L;
        private Long no;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentSchool {
        private static final long serialVersionUID = 3997395302485226876L;
        private Long sno;
        private String sname;
    }
}

// Teacher.java
package blog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Teacher {
    private static final long serialVersionUID = 4976235843125918808L;
    private String id;
    private String name;
}

// SchoolBag.java
package blog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchoolBag {
    private static final long serialVersionUID = -7699349163224691756L;
    private String id;
    private String name;
}
```

```sql
-- student.sql
CREATE TABLE student(
                        id         integer(10) not null,
                        name       varchar(32) not null,
                        class_no   integer,
                        class_name text,
                        school_no integer,
                        school_name varchar(32));

CREATE TABLE school_bag (
                            ID INTEGER,
                            NAME VARCHAR,
                            STUDENT_ID INTEGER
);

CREATE TABLE teacher (
                         ID INTEGER,
                         NAME VARCHAR,
                         STUDENT_ID INTEGER
);

INSERT INTO student (id,name,class_no,class_name,school_no,school_name) VALUES
    (99,'小张张99',1,'一年一班',1,'测试学校');

INSERT INTO school_bag (ID,NAME,STUDENT_ID) VALUES
                                                (1,'书包 1',99),
                                                (2,'书包 2',99),
                                                (3,'书包 3',99);

INSERT INTO teacher (ID,NAME,STUDENT_ID) VALUES
    (1,'班主任 1',99);
```

```java
// StudentMyBatisController.java
package blog;

import org.springframework.web.bind.annotation.*;


@RestController
public class StudentMyBatisController {
    private final StudentNewMapper studentNewMapper;

    public StudentMyBatisController(StudentNewMapper studentNewMapper) {
        this.studentNewMapper = studentNewMapper;
    }

    @RequestMapping(value = "/studentXML", method = RequestMethod.GET)
    public Object getStudentXML(@RequestParam Long id){
        return studentNewMapper.getStudent(id);
    }

    @RequestMapping(value = "/studentsXML", method = RequestMethod.GET)
    public Object getStudentsXML(){
        return studentNewMapper.getStudents();
    }

    @RequestMapping(value = "/studentXML", method = RequestMethod.POST)
    public String addStudentXML(Student student){
        studentNewMapper.saveStudent(student);
        return "ok";
    }

    @RequestMapping(value = "/studentXML", method = RequestMethod.PUT)
    public String updateStudentXML(Student student){
        studentNewMapper.updateStudent(student);
        return "ok";
    }

    @RequestMapping(value = "/studentXML", method = RequestMethod.DELETE)
    public String deleteStudentXML(String id){
        studentNewMapper.deleteStudent(id);
        return "ok";
    }
}

// StudentNewMapper.java
package blog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StudentNewMapper {
    Student getStudent(@Param("id") Long id);
    List<Student> getStudents();
    void saveStudent(@Param("student") Student student);
    void updateStudent(@Param("student") Student student);
    void deleteStudent(@Param("id") String id);
}
```

```xml
<!-- src/main/resources/mapper/StudentNewMapper.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="blog.StudentNewMapper">
    <resultMap type="blog.Student" id="student">
        <result column="id" property="id"/>
        <result column="name" property="name"/>
        <association property="studentClass" columnPrefix="class_" autoMapping="true"/>
        <association property="studentSchool" columnPrefix="school_" autoMapping="true">
            <result property="sno" column="no" />
            <result property="sname" column="name" />
        </association>
        <association property="teacher" columnPrefix="teacher_" autoMapping="true">
            <result property="id" column="id" />
            <result property="name" column="name" />
        </association>
        <collection property="schoolBags" javaType="java.util.List" ofType="blog.SchoolBag">
            <id property="id" column="school_bag_id"/>
            <result property="name" column="school_bag_name"/>
        </collection>
    </resultMap>

    <sql id="studentDetail">
        select t.id,
               t.name,
               t.class_no,
               t.class_name,
               t.school_no,
               t.school_name,
               s.id AS school_bag_id,
               s.name AS school_bag_name,
               e.id AS teacher_id,
               e.name AS teacher_name
        from student t
                 left join school_bag s on t.id = s.student_id
                 left join teacher e on t.id = e.student_id
    </sql>
    <select id="getStudent" parameterType="Long" resultMap="student">
        <include refid="studentDetail"></include>
        where t.id = #{id}
    </select>
    <select id="getStudents" resultMap="student">
        <include refid="studentDetail"></include>
    </select>
    <insert id="saveStudent">
        insert into student
        (id,
         name,
         class_no,
         class_name,
         school_no,
         school_name)
        values
            (#{student.id},
             #{student.name},
             #{student.studentClass.no},
             #{student.studentClass.name},
             #{student.studentSchool.sno},
             #{student.studentSchool.sname})
    </insert>
    <update id="updateStudent">
        update student
        set name = #{student.name},
            class_no= #{student.studentClass.no},
            class_name= #{student.studentClass.name},
            school_no= #{student.studentSchool.sno},
            school_name= #{student.studentSchool.sname}
        where id = #{student.id}
    </update>
    <delete id="deleteStudent">
        delete from student where id = #{id}
    </delete>
</mapper>
```

## JPA(Java Persistence API)

- JpaRepository가 이 CRUD 메서드들을 이미 정의해두었고
- Spring Data JPA가 애플리케이션 기동 시 이 인터페이스의 구현체(프록시)를 자동 생성해 빈으로 등록합니다.
- 즉 "인터페이스 선언 → 구현은 프레임워크가 런타임에 생성" 구조입니다.

```yaml
# application.yaml
spring:
  datasource:
    driver-class-name: org.sqlite.JDBC
    url: jdbc:sqlite:/Users/julong/Documents/be-dev/blog/student.db
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect

    hibernate:
      ddl-auto: update

    show-sql: true
```

```java
// StudentJpa.java
package blog.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "student")
public class StudentJpa {
    @Id
    @Column(name = "id")
    private String id;
    @Column(name = "name")
    private String name;
    public StudentJpa() {
    }
    public StudentJpa(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

// StudentJpaController.java
package blog.jpa;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
public class StudentJpaController {

    @Autowired
    private StudentJpaRepository studentJpaRepository;

    @GetMapping("/studentJpa")
    public Object getStudent(@RequestParam String id){
        Optional<StudentJpa> opt = studentJpaRepository.findById(id);
        if(opt.isPresent()){
            return opt.get();
        }
        return null;
    }

    @PostMapping("/studentJpa")
    public String addStudent(@RequestParam String id, @RequestParam String name){
        studentJpaRepository.save(new StudentJpa(id,name));
        return "ok";
    }

    @PutMapping("/studentJpa")
    public String updateStudent(@RequestParam String id, @RequestParam String name){
        studentJpaRepository.save(new StudentJpa(id,name));
        return "ok";
    }

    @DeleteMapping("/studentJpa")
    public String deleteStudent(@RequestParam String id){
        studentJpaRepository.deleteById(id);
        return "ok";
    }
}


// StudentJpaRepository.java
package blog.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StudentJpaRepository extends JpaRepository<StudentJpa, String> {
}

```

## RestTemplate

```java
// RestTemplateConfig.java
package blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(){

        return new RestTemplate();
    }

}

// RandomUserService.java
package blog.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class RandomUserService {

    private final RestTemplate restTemplate;

    public RandomUserService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String getRandomUser(int results){

        String url =
                "https://randomuser.me/api/?results={results}";

        return restTemplate.getForObject(
                url,
                String.class,
                results
        );

    }

}

// UserController.java
package blog.service;

import blog.service.RandomUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final RandomUserService randomUserService;

    public UserController(RandomUserService randomUserService) {
        this.randomUserService = randomUserService;
    }

    @GetMapping("/random-user")
    public String randomUser(
            @RequestParam(defaultValue = "1") int results){

        return randomUserService.getRandomUser(results);

    }
}
```

## ehcache

```java
// build.gradle
implementation 'org.springframework.boot:spring-boot-starter-cache'
implementation 'javax.cache:cache-api'
implementation 'org.ehcache:ehcache::jakarta'
```

```yaml
spring:
  cache:
    jcache:
      config: classpath:ehcache.xml
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<config
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xmlns='http://www.ehcache.org/v3'
        xmlns:jsr107='http://www.ehcache.org/v3/jsr107'
        xsi:schemaLocation="
            http://www.ehcache.org/v3 http://www.ehcache.org/schema/ehcache-core-3.10.xsd
            http://www.ehcache.org/v3/jsr107 http://www.ehcache.org/schema/ehcache-107-ext-3.10.xsd">
    <cache alias="randomUser">
        <key-type>java.lang.Integer</key-type>
        <value-type>java.lang.String</value-type>
        <expiry>
            <ttl unit="minutes">10</ttl>
        </expiry>
        <resources>
            <heap unit="entries">100</heap>
        </resources>
    </cache>
</config>
```

```java
// HelloWorldApplication.java <= intro
package blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching; // <= 추가됨

@EnableCaching  // <= 추가됨
@SpringBootApplication
public class HelloWorldApplication {

	public static void main(String[] args) {
		SpringApplication.run(HelloWorldApplication.class, args);
	}

}

// RandomUserService.java
package blog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class RandomUserService {

    private static final Logger log = LoggerFactory.getLogger(RandomUserService.class);

    private final RestTemplate restTemplate;

    public RandomUserService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    // results 값이 캐시에 있으면 외부 API를 호출하지 않고 캐시된 응답을 반환
    @Cacheable(cacheNames = "randomUser", key = "#results")
    public String getRandomUser(int results){
        // 이 로그가 찍히면 캐시 미스 = 실제 외부 API 호출
        log.info("CACHE MISS - randomuser.me 호출 (results={})", results);

        String url =
                "https://randomuser.me/api/?results={results}";

        return restTemplate.getForObject(
                url,
                String.class,
                results
        );
    }
}
```

## application.yaml

```yaml
test.properties.value:
  a: 나는a
  b: 나는b
  a1: 나는a1
  b1: 나는b1
  test-a: 나는test-a
```

```java
// EnvConfig.java
package blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "test.properties.value")
public class EnvConfig {
    private String a1;
    private String b1;
    private String testA;

    public String getA1() {
        return a1;
    }

    public void setA1(String a1) {
        this.a1 = a1;
    }

    public String getB1() {
        return b1;
    }

    public void setB1(String b1) {
        this.b1 = b1;
    }

    public String getTestA() {
        return testA;
    }

    public void setTestA(String testA) {
        this.testA = testA;
    }
}

// GetEnvConfigController.java
package blog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import blog.config.EnvConfig;

@Slf4j
@RestController
public class GetEnvConfigController {

    @Autowired
    private EnvConfig envConfig;

    @Value("${test.properties.value.a}")
    private String a;
    @Value("${test.properties.value.b}")
    private String b;

    @GetMapping("/envConfig")
    public String getEnvConfig(){
        log.info("a1={};b1={};test-a={}", envConfig.getA1(),
                envConfig.getB1(), envConfig.getTestA());
        return  "a1=" + envConfig.getA1() + ";b1="
                + envConfig.getB1() + ";test-a="
                + envConfig.getTestA();
    }
}
```


## logback

```xml
<!-- src/main/resources/application.yaml-->
<?xml version="1.0" encoding="UTF-8"?>
<configuration scan="false" scanPeriod="60 seconds" debug="false">

    <property name="LOG_HOME" value="logs"/>
    <property name="appName" value="blog"/>

    <appender name="stdout" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>[%d{yyyy-MM-dd HH:mm:ss}] [%thread] [%level] %logger{50} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="appLogAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_HOME}/${appName}-%d{yyyy-MM-dd}-%i.log</fileNamePattern>
            <maxHistory>365</maxHistory>
            <maxFileSize>100MB</maxFileSize>
        </rollingPolicy>

        <encoder>
            <pattern>[%d{yyyy-MM-dd HH:mm:ss}] [%thread] [%level] %logger{50} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="blog" level="debug"/>

    <root level="info">
        <appender-ref ref="stdout"/>
        <appender-ref ref="appLogAppender"/>
    </root>

</configuration>
```

```java
// TestLogbackController.java
package blog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class TestLogbackController {

    @GetMapping("/logback")
    public String logback(){
        log.info("info 레벨 - 중요한 정보 출력, 가장 많이 사용");
        log.debug("debug 레벨 - 디버깅용, 보통 실무에서 최저 레벨로 사용");
        log.error("error 레벨 - 에러 로그 기록, 자주 사용");
        log.warn("warn 레벨 - 경고 로그 기록, 자주 사용");
        log.trace("trace 레벨 - 실행 흐름 추적용, 거의 사용하지 않음");

        int param1 = 1;
        String param2 = "param2";
        // {} placeholder로 파라미터를 치환해 여러 값을 함께 기록할 수 있다
        log.info("전달 파라미터: {}, 두 번째 파라미터: {}", param1, param2);

        return "ok";
    }
}

```

### 로그 레벨

| 순번 | 로그 레벨 | 설명 |
|------|-----------|------|
| 1 | trace | 추적. 프로그램 실행 흐름을 나타낸다. |
| 2 | debug | 디버그. 실무에서는 보통 최저 레벨로 사용하며, trace는 거의 쓰지 않는다. |
| 3 | info | 중요한 정보 출력. 많이 사용한다. |
| 4 | warn | 경고. 많이 사용한다. |
| 5 | error | 에러 정보. 많이 사용한다. |

### 자주 쓰는 로그 출력 포맷 파라미터

| 순번 | 출력 포맷 | 설명 |
|------|-----------|------|
| 1 | `%d{yyyy-MM-dd HH:mm:ss, SSS}` | 로그 생성 시간. 밀리초까지 출력 |
| 2 | `%level` | 로그 레벨 출력 |
| 3 | `%logger` 또는 `%c` | logger 이름. 보통 패키지명 + 클래스명 |
| 4 | `%thread` 또는 `%t` | 현재 스레드 이름 출력 |
| 5 | `%p` | 로그 레벨(우선순위) 출력. `%level`과 동일 |
| 6 | `%message` 또는 `%msg` 또는 `%m` | 로그 내용. 예: `logger.info("message")` |
| 7 | `%n` | 줄바꿈 문자 |
| 8 | `%class` 또는 `%C` | Java 클래스명 출력 |
| 9 | `%file` 또는 `%F` | 파일명 출력 |
| 10 | `%L` | 로그 발생 행 번호 출력 |
| 11 | `%method` 또는 `%M` | 메서드명 출력 |
| 12 | `%l` | 구문이 위치한 행 정보 출력(클래스명, 메서드명, 파일명, 행 번호 포함) |
| 13 | `hostName` | 로컬 머신 이름 |
| 14 | `hostAddress` | 로컬 IP 주소 |
