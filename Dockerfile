# Use an official OpenJDK runtime as the base image
FROM openjdk:21-jdk-slim

RUN apt update -y && apt install git cmake g++ libboost1.81-all-dev -y

RUN git clone https://github.com/trolando/oink && \
    cd oink && \
    mkdir build && cd build && \
    cmake .. && make && \
    ln -s /oink/build/oink /usr/bin/oink

# Set the working directory inside the container
WORKDIR /app

# Copy the Gradle Wrapper files and project files to the container
COPY . .

# Grant execution permissions to the Gradle Wrapper
RUN chmod +x ./gradlew

RUN ./gradlew installDist

ENTRYPOINT ["build/install/cges/bin/cges"]
