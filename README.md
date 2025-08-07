# CGES - Concurrent Game Equilibrium Solver

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## ExCoS 2025 Workshop Materials

- **Extended Abstract**: [ExCoS2025-ExtendedAbstract.pdf](https://github.com/incaseoftrouble/CGES/blob/master/ExCoS2025-ExtendedAbstract.pdf)
- **Presentation Slides**: [ExCoS2025-Presentation.pdf](https://github.com/incaseoftrouble/CGES/blob/master/ExCoS2025-Presentation.pdf)

---

## Overview

CGES (Concurrent Game Equilibrium Solver) is a Java-based tool for computing and explaining Nash equilibria in multi-agent systems with Linear Temporal Logic (LTL) objectives. Version 0.1 focuses on improving both performance and explainability in rational synthesis through efficient algorithms and comprehensive visualization options.

## Features

- **Efficient Nash Equilibrium Computation**:
  - Implements a novel algorithm that significantly outperforms existing solutions
  - Multiple game type support (Concurrent, History, Suspect games)
  - Memory-efficient processing option for large games

- **Input/Output Flexibility**:
  - Multiple game input formats (JSON and explicit format)
  - DOT format visualization for game structures and solutions
  - Module-based game processing
  - Validation support for expected results

- **Explainability Features**:
  - Supports contrastive explanations through "why/not" questions
  - Clear operational semantics for agent interactions
  - Comprehensive visualization options

## Requirements

- Java 17 or higher
- Gradle (build tool)
- Docker runtime (optional)
- Dependencies:
  - OINK solver
  - Boost libraries (for Docker build)
  - Z3 Solver
  - Owl (LTL translation)

## Installation

### Using Gradle
```bash
./gradlew installDist
```

### Using Docker
```bash
docker build -t cges .
```

### Building from Source
```bash
git clone https://github.com/incaseoftrouble/cges.git
cd cges
./gradlew build
```

## Usage

### Basic Command Structure
```bash
cges [OPTIONS] --game <file.json> | --game-explicit <file>
```

### Docker Usage
```bash
docker run cges [OPTIONS] --game <file.json>
```

### Command Options
```
--game               Input game in JSON format
--game-explicit      Input game in explicit format
--write-dot-cg      Write the concurrent game in DOT format
--write-dot-hg      Write the history game in DOT format
--write-dot-sg      Write the suspect game in DOT format
--write-dot-sol     Write the solutions in DOT format
-O, --output        Write the assignments with nash equilibria (default: stdout)
--rg-solver         Solver to search for a lasso
--memory            Conserve memory by not storing solutions
```

## Performance

Benchmark results comparing CGES with [EVE](https://github.com/eve-mas/eve-parity) on the gossip protocol:

| Players | 2    | 3    | 4    | 5     | 6      | 7       | 8       |
|---------|------|------|------|--------|---------|----------|----------|
| CGES    | 0.2s | 0.4s | 0.6s | 1.4s  | 3.8s   | 20.1s   | 167.3s  |
| EVE     | 0.1s | 0.2s | 1.1s | 13.5s | 310.4s | >2 hours | N/A     |

## Algorithm

CGES implements a three-step approach:
1. Constructs a suspect game from the concurrent game
2. Transforms it into a parity game for solving
3. Computes equilibrium strategy profiles

## Contributing

Contributions are welcome! Please feel free to submit pull requests or create issues for bugs and feature requests.

To contribute:
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request


## Contact

For questions and support, please contact:
- **Email**: alireza.farhadi@gmail.com

## Acknowledgments

This work utilizes several open-source tools:
- [Owl](https://github.com/owl-toolkit/owl) for LTL translation
- [Oink](https://github.com/trolando/oink) for parity game solving
- [Z3](https://github.com/Z3Prover/z3) for SAT solving
- [EVE](https://github.com/eve-mas/eve-parity) for benchmarking comparison


Last Updated: 2025-08-07 by @arfarhadi
