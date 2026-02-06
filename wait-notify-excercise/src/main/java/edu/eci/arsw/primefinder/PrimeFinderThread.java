package edu.eci.arsw.primefinder;

import java.util.LinkedList;
import java.util.List;

public class PrimeFinderThread extends Thread{


    int a,b;

    private List<Integer> primes;

    private final PauseController controller;

    public PrimeFinderThread(int a, int b,PauseController controller) {
        super();
        this.primes = new LinkedList<>();
        this.a = a;
        this.b = b;
        this.controller = controller;
    }

    @Override
    public void run(){
        try {
            for (int i= a;i < b;i++){
                controller.awaitIfPaused();
                if (isPrime(i)){
                    primes.add(i);
                    System.out.println(i);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }



    boolean isPrime(int n) {
        boolean ans;
        if (n > 2) {
            ans = n%2 != 0;
            for(int i = 3;ans && i*i <= n; i+=2 ) {
                ans = n % i != 0;
            }
        } else {
            ans = n == 2;
        }
        return ans;
    }

    public List<Integer> getPrimes() {
        return primes;
    }

}
