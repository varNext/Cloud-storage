package com.cloud.model;

import jakarta.persistence.*;
import lombok.*;


import java.time.LocalDateTime;


@Entity
@Table(name = "files", indexes = {
        @Index(name = "idx_files_user_id_file", columnList = "user_id, id")
})
@Getter
@NoArgsConstructor
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserFiles {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String fileName;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @Column(nullable = false)
    private String filePath;                 // UUID.bin — физическое имя на диске

    @Column(name = "content_type", length = 100)
    private String contentType;


    //LAZY грузит Users только при явном обращении
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)


    private Users user;

    public UserFiles(String fileName, long fileSize, String filePath,
                     String contentType, Users user) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.filePath = filePath;
        this.contentType = contentType;
        this.user = user;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserFiles)) return false;
        UserFiles other = (UserFiles) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @PrePersist
    void prePersist() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }
}

