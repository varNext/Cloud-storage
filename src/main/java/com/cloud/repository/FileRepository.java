package com.cloud.repository;

import com.cloud.model.UserFiles;
import com.cloud.model.Users;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface FileRepository extends JpaRepository<UserFiles, Long> {

    // @Query("SELECT f FROM File f WHERE f.users = :users")
    //List<File> findByUser (@Param("users") Users user);

    List<UserFiles> findByUser(Users user);

    Optional<UserFiles> findByIdAndUser(Long id, Users user);
}

