package rs.ac.bg.etf.kdp.oddperson;

import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.lindaclient.LindaFactory;

import java.io.Serial;
import java.io.Serializable;

public class Main {

	public static void main(String[] args) {

		Linda linda = LindaFactory.get();

		System.out.println("GAME IS ABOUT TO START");

		int coin = (int) (Math.random() * 2);
		System.out.println(coin);

//        linda.in(new String[]{"NONEXISTING"});

		for (int i = 0; i < 3; i++) {
			linda.out(new String[]{"PID", String.valueOf(0)});
		}


//        out("PID", 0);
//        out("PID", 0);
//        out("PID", 0);

		for (int i = 0; i < 3; i++) {
			linda.eval("player", new Player(i));
		}
//        eval(Player(0));
//        eval(Player(1));
//        eval(Player(2))

		// wait for results from workers/players
		for (int i = 0; i < 3; i++) {
			String[] winnerTemplate = {"WINNER", String.valueOf(i), null};
			linda.in(winnerTemplate);

			System.out.println("Player with id: " + winnerTemplate[1] + " is winner: " + winnerTemplate[2]);
		}

		System.out.println("GAME FINISHED");
	}

	public final static class Player implements Serializable, Runnable {
		@Serial
		private static final long serialVersionUID = 2853821600385432860L;

		private final int id;

		public Player(int id) {
			this.id = id;
		}


		@Override
		public void run() {
			int PID;
			int coin, coinl, coinr;
			boolean end, winner;

			Linda linda = LindaFactory.get();

			String[] pidTemplate = {"PID", null};

			linda.in(pidTemplate);
//            in("PID", ?PID)

			PID = Integer.parseInt(pidTemplate[1]);
			do {
				PID++;
				coin = (int) ((Math.random() * 2));
//                coin = (int)((rand() * 2) / RAND_MAX);
				linda.out(new String[]{"RESULT", String.valueOf(this.id), String.valueOf(PID), String.valueOf(coin)});
				linda.out(new String[]{"RESULT", String.valueOf(this.id), String.valueOf(PID), String.valueOf(coin)});
//                out("RESULT", id, PID, coin);
//                out("RESULT", id, PID, coin);
				String[] firstResultTemplate = {"RESULT", String.valueOf((this.id + 1) % 3), String.valueOf(PID), null};
				linda.in(firstResultTemplate);

				coinr = Integer.parseInt(firstResultTemplate[3]);

				String[] secondResultTemplate = {"RESULT", String.valueOf((this.id + 2) % 3), String.valueOf(PID), null};
				linda.in(secondResultTemplate);

				coinl = Integer.parseInt(secondResultTemplate[3]);

//                in("RESULT", (id + 1) % 3, PID, ?coinr);
//                in("RESULT", (id + 2) % 3, PID, ?coinl);

				end = !((coin == coinl) && (coin == coinr) && (coinl == coinr));
				winner = (coin != coinl) && (coin != coinr);
			} while (!end);

			linda.out(new String[]{"WINNER", String.valueOf(this.id), String.valueOf(winner)});
		}
	}
}