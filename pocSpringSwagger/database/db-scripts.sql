CREATE DATABASE filestorage;


CREATE TABLE Repositories(
	repositoryId VARCHAR(20) PRIMARY KEY,
	name VARCHAR(50) NOT NULL,
)

CREATE TABLE Files(
	fileId VARCHAR(20) PRIMARY KEY,
	name VARCHAR(50),
	description VARCHAR(100),
	sha256 CHAR(64),
	sizeInBytes INTEGER,
	repositoryId VARCHAR(20) FOREIGN KEY REFERENCES Repositories(repositoryId)
);


