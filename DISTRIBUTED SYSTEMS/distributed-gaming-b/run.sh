#!/bin/bash
# ==============================================================
# run.sh - Εκκίνηση των components του Distributed Gaming System.
# ==============================================================
# Χρήση: ./run.sh <component> [args...]
#
# Παραδείγματα:
#   ./run.sh reducer               <- Εκκίνηση Reducer
#   ./run.sh srg                   <- Εκκίνηση SRG
#   ./run.sh worker 0 3 6000       <- Worker 0 από 3, πόρτα 6000
#   ./run.sh worker 1 3 6001       <- Worker 1 από 3, πόρτα 6001
#   ./run.sh worker 2 3 6002       <- Worker 2 από 3, πόρτα 6002
#   ./run.sh master                <- Εκκίνηση Master
#   ./run.sh manager               <- Εκκίνηση Manager Console
#   ./run.sh player                <- Εκκίνηση Player Console
# ==============================================================

# Φάκελος με τα μεταγλωττισμένα class αρχεία
CLASSPATH="out"

case "$1" in
    reducer)
        echo "Εκκίνηση Reducer..."
        java -cp $CLASSPATH reducer.ReducerServer config.conf
        ;;
    srg)
        echo "Εκκίνηση Secured Random Generator (SRG)..."
        java -cp $CLASSPATH randomgen.RandomGeneratorServer
        ;;
    worker)
        INDEX=${2:-0}
        TOTAL=${3:-3}
        PORT=${4:-6000}
        echo "Εκκίνηση Worker[$INDEX/$TOTAL] στην πόρτα $PORT..."
        java -cp $CLASSPATH worker.WorkerServer $INDEX $TOTAL $PORT config.conf
        ;;
    master)
        echo "Εκκίνηση Master Server..."
        java -cp $CLASSPATH master.MasterServer workers.conf
        ;;
    manager)
        echo "Εκκίνηση Manager Console..."
        java -cp $CLASSPATH manager.ManagerClient config.conf
        ;;
    player)
        echo "Εκκίνηση Player Console..."
        java -cp $CLASSPATH player.DummyPlayerClient config.conf
        ;;
    *)
        echo "Χρήση: ./run.sh <reducer|srg|worker|master|manager|player> [args...]"
        echo ""
        echo "  reducer               - Εκκίνηση Reducer Server (πόρτα 8001)"
        echo "  srg                   - Εκκίνηση SRG Server (πόρτα 7001)"
        echo "  worker [index] [total] [port] - Εκκίνηση Worker"
        echo "  master                - Εκκίνηση Master Server (πόρτα 5001)"
        echo "  manager               - Εκκίνηση Manager Console"
        echo "  player                - Εκκίνηση Player Console"
        ;;
esac
