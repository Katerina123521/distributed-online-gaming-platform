package com.example.distributedgamingapp;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import common.Request;
import common.Response;
import model.Bet;
import model.Game;
import model.SearchFilter;

public class MainActivity extends AppCompatActivity {

    private String playerId = "PlayerGamer1";
    private double balance = 0.0;

    private TextView tvPlayerInfo;
    private TextView tvStatus;
    private Button btnAddBalance;
    private EditText etStars;
    private Spinner spRiskLevel;
    private Spinner spBetCategory;
    private Button btnSearch;
    private ListView lvGames;

    private List<Game> gameList = new ArrayList<>();
    private GameListAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvPlayerInfo = findViewById(R.id.tvPlayerInfo);
        tvStatus = findViewById(R.id.tvStatus);
        btnAddBalance = findViewById(R.id.btnAddBalance);
        etStars = findViewById(R.id.etStars);
        spRiskLevel = findViewById(R.id.spRiskLevel);
        spBetCategory = findViewById(R.id.spBetCategory);
        btnSearch = findViewById(R.id.btnSearch);
        lvGames = findViewById(R.id.lvGames);

        updatePlayerInfo();

        ArrayAdapter<String> riskAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"Όλα", "LOW", "MEDIUM", "HIGH"});
        riskAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRiskLevel.setAdapter(riskAdapter);

        ArrayAdapter<String> betAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"Όλα", "$", "$$", "$$$"});
        betAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBetCategory.setAdapter(betAdapter);

        adapter = new GameListAdapter();
        lvGames.setAdapter(adapter);

        btnAddBalance.setOnClickListener(v -> showAddBalanceDialog());
        btnSearch.setOnClickListener(v -> performSearch());
    }

    private void updatePlayerInfo() {
        tvPlayerInfo.setText(String.format("Παίκτης: %s\nΥπόλοιπο: %.2f FUN", playerId, balance));
    }

    private void showAddBalanceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Προσθήκη FUN Tokens");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        builder.setView(input);

        builder.setPositiveButton("Προσθήκη", (dialog, which) -> {
            try {
                double amount = Double.parseDouble(input.getText().toString());
                if (amount > 0) {
                    balance += amount;
                    updatePlayerInfo();
                    Toast.makeText(this, "Προστέθηκαν " + amount + " FUN", Toast.LENGTH_SHORT).show();
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Μη έγκυρο ποσό", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Ακύρωση", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void performSearch() {
        Double stars = null;
        if (!etStars.getText().toString().isEmpty()) {
            stars = Double.parseDouble(etStars.getText().toString());
        }

        String risk = spRiskLevel.getSelectedItem().toString();
        if (risk.equals("Όλα")) risk = null;

        String betCat = spBetCategory.getSelectedItem().toString();
        if (betCat.equals("Όλα")) betCat = null;

        SearchFilter filter = new SearchFilter(stars, risk, betCat);
        Request req = new Request("SEARCH", filter);

        tvStatus.setText("Αναζήτηση...");

        PlayerSocketClient.sendRequestAsync(req, new PlayerSocketClient.SocketCallback() {
            @Override
            public void onSuccess(Response response) {
                runOnUiThread(() -> {
                    if ("OK".equals(response.getStatus())) {
                        gameList.clear();
                        gameList.addAll((List<Game>) response.getPayload());
                        adapter.notifyDataSetChanged();
                        tvStatus.setText("Βρέθηκαν " + gameList.size() + " παιχνίδια.");
                    } else {
                        tvStatus.setText("Σφάλμα: " + response.getPayload());
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> tvStatus.setText(error));
            }
        });
    }

    private void showPlayDialog(Game game) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Ποντάρισμα - " + game.getGameName());
        builder.setMessage(String.format("Min: %.2f, Max: %.2f", game.getMinBet(), game.getMaxBet()));

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        builder.setView(input);

        builder.setPositiveButton("Παίξε", (dialog, which) -> {
            try {
                double amount = Double.parseDouble(input.getText().toString());
                if (amount < game.getMinBet() || amount > game.getMaxBet()) {
                    Toast.makeText(this, "Εκτός ορίων πονταρίσματος!", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (amount > balance) {
                    Toast.makeText(this, "Δεν επαρκεί το υπόλοιπο!", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                balance -= amount;
                updatePlayerInfo();

                Bet bet = new Bet(game.getGameName(), amount, playerId);
                Request req = new Request("PLAY", bet);

                tvStatus.setText("Εκτέλεση πονταρίσματος...");

                PlayerSocketClient.sendRequestAsync(req, new PlayerSocketClient.SocketCallback() {
                    @Override
                    public void onSuccess(Response response) {
                        runOnUiThread(() -> {
                            if ("OK".equals(response.getStatus())) {
                                String resultStr = (String) response.getPayload();
                                // Parse -> Bet: X | Won: Y | Net: Z
                                try {
                                    String[] parts = resultStr.split("\\|");
                                    double wonAmount = Double.parseDouble(parts[1].split(":")[1].trim());
                                    balance += wonAmount;
                                    updatePlayerInfo();
                                    
                                    new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("Αποτέλεσμα")
                                        .setMessage(resultStr + "\nΝέο υπόλοιπο: " + balance + " FUN")
                                        .setPositiveButton("ΟΚ", null)
                                        .show();
                                    tvStatus.setText("Ποντάρισμα ολοκληρώθηκε.");
                                } catch (Exception e) {
                                    tvStatus.setText("Σφάλμα στο parsing: " + resultStr);
                                }
                            } else {
                                balance += amount; // Επιστροφή χρημάτων
                                updatePlayerInfo();
                                tvStatus.setText("Αποτυχία: " + response.getPayload());
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            balance += amount; // Επιστροφή χρημάτων
                            updatePlayerInfo();
                            tvStatus.setText(error);
                        });
                    }
                });

            } catch (NumberFormatException e) {
                Toast.makeText(this, "Μη έγκυρο ποσό", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Ακύρωση", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showRateDialog(Game game) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Βαθμολογία - " + game.getGameName());

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 έως 5");
        builder.setView(input);

        builder.setPositiveButton("Υποβολή", (dialog, which) -> {
            try {
                int rating = Integer.parseInt(input.getText().toString());
                if (rating < 1 || rating > 5) {
                    Toast.makeText(this, "Το νούμερο πρέπει να είναι από 1 ως 5.", Toast.LENGTH_SHORT).show();
                    return;
                }

                Request req = new Request("RATE_GAME", new Object[]{game.getGameName(), rating});
                
                PlayerSocketClient.sendRequestAsync(req, new PlayerSocketClient.SocketCallback() {
                    @Override
                    public void onSuccess(Response response) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Η βαθμολογία υποβλήθηκε!", Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Σφάλμα: " + error, Toast.LENGTH_SHORT).show());
                    }
                });
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Μη έγκυρο νούμερο", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Ακύρωση", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private class GameListAdapter extends ArrayAdapter<Game> {
        public GameListAdapter() {
            super(MainActivity.this, R.layout.activity_game_item, gameList);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.activity_game_item, parent, false);
            }

            Game game = getItem(position);

            TextView tvTitle = convertView.findViewById(R.id.tvGameTitle);
            TextView tvDetails = convertView.findViewById(R.id.tvGameDetails);
            Button btnPlay = convertView.findViewById(R.id.btnPlay);
            Button btnRate = convertView.findViewById(R.id.btnRate);

            tvTitle.setText(game.getGameName());
            tvDetails.setText(String.format("Risk: %s | Stars: %.1f\nLimits: %.2f - %.2f", game.getRiskLevel(), game.getStars(), game.getMinBet(), game.getMaxBet()));

            btnPlay.setOnClickListener(v -> showPlayDialog(game));
            btnRate.setOnClickListener(v -> showRateDialog(game));

            return convertView;
        }
    }
}