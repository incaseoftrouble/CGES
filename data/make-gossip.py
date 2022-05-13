import sys
import json

if __name__ == "__main__":
    n = int(sys.argv[1])
    json.dump({
        "name": f"gossip{n}",
        "ap": [f"g{i}" for i in range(1, n + 1)],
        "type": "module",
        "goal": f"F G ({' & '.join(f'g{i}' for i in range(1, n + 1))})",
        "modules": {
            f"A{i}": {
                "goal": f"G F g{i}",
                "payoff": "?",
                "actions": ["a", "g", "w"],
                "labels": [f"g{i}"],
                "initial": "s1",
                "states": {
                    "s1": {
                        "labels": [],
                        "transitions": [
                            {
                                "action": "a",
                                "guard": "true",
                                "to": "s1"
                            },
                            {
                                "action": "g",
                                "guard": "true",
                                "to": "s2"
                            }
                        ]
                    },
                    "s2": {
                        "labels": [f"g{i}"],
                        "transitions": [
                            {
                                "action": "w",
                                "guard": " & ".join(f"!g{j}" for j in range(1, n+1) if j != i),
                                "to": "s2"
                            },
                            {
                                "action": "g",
                                "guard": " | ".join(f"g{j}" for j in range(1, n+1) if j != i),
                                "to": "s1"
                            }
                        ]
                    }
                }
            } for i in range(1, n+1)
        }
    }, sys.stdout)
